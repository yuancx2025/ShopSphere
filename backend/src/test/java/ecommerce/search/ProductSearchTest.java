package ecommerce.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import ecommerce.dto.product.ProductUpdateDto;
import ecommerce.model.Category;
import ecommerce.repository.CategoryRepository;
import ecommerce.service.ProductService;
import ecommerce.support.MySqlIntegrationTest;
import ecommerce.support.SharedContainers;
import ecommerce.support.TestData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Sprint 3 merge gate (PR 07): both engines return the intended results for the same filters, admin writes reach
 * Elasticsearch through the job table, replayed or stale writes never regress a document, and queued jobs survive
 * an Elasticsearch outage and a restart of the indexer.
 *
 * <p>The scheduler is off, so each test drains the queue itself and the order of events is exact.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"search.elasticsearch.enabled=true", "search.indexer.schedule=false",
        "spring.elasticsearch.socket-timeout=2s"})
class ProductSearchTest extends MySqlIntegrationTest {

    static final ElasticsearchContainer ES = SharedContainers.elasticsearch();

    @DynamicPropertySource
    static void elasticsearch(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", () -> "http://" + ES.getHttpHostAddress());
    }

    static final int FURNITURE = TestData.CATEGORY_ID;
    static final int LIGHTING = 2;
    static final int BRASS_LAMP = 1;
    static final int DESK = 2;
    static final int FLOOR_LAMP = 3;
    static final int LED_LAMPS = 4;
    static final int BOOKSHELF = 5;

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    ProductIndexer indexer;
    @Autowired
    SearchIndexJobs jobs;
    @Autowired
    MySqlSearchEngine mysql;
    @Autowired
    ElasticsearchClient client;
    @Autowired
    SearchProperties props;
    @Autowired
    ProductService productService;
    @Autowired
    CategoryRepository categories;

    TestData data;

    @BeforeEach
    void seed() throws Exception {
        data = new TestData(jdbc).reset();
        data.category(LIGHTING, "Lighting");
        data.product(BRASS_LAMP, LIGHTING, "Brass desk lamp", "Adjustable arm, warm light", "40.00", 5);
        data.product(DESK, FURNITURE, "Walnut writing desk", "Solid walnut with two drawers", "250.00", 3);
        data.product(FLOOR_LAMP, LIGHTING, "Floor lamp with linen shade", "Tall reading light", "90.00", 7);
        data.product(LED_LAMPS, LIGHTING, "LED lamps for reading", "Set of two clip-on lights", "25.00", 9);
        data.product(BOOKSHELF, FURNITURE, "Oak bookshelf", "Five shelves", "120.00", 2);
        indexer.rebuild();
        indexer.drainAll();
        indexer.refresh();
    }

    @Test
    void bothEnginesApplyTheSameFiltersAndSort() throws Exception {
        for (String engine : List.of("mysql", "elasticsearch")) {
            JsonNode page = search(get("/product/search").param("q", "lamp").param("categoryId", "2")
                    .param("minPrice", "30").param("maxPrice", "100").param("sort", "price_asc")
                    .param("engine", engine));
            assertThat(ids(page)).as(engine).containsExactly(BRASS_LAMP, FLOOR_LAMP);
            assertThat(page.get("engine").asText()).isEqualTo(engine);
            assertThat(page.get("items").get(0).get("price").decimalValue()).isEqualByComparingTo("40.00");
            assertThat(page.get("items").get(0).get("stock").asInt()).isEqualTo(5);
        }
    }

    @Test
    void elasticsearchStemsWhereFulltextMatchesWholeWords() throws Exception {
        // The english analyzer reduces "lamps" to "lamp". InnoDB FULLTEXT only matches the exact token.
        assertThat(ids(search(get("/product/search").param("q", "lamp").param("engine", "mysql"))))
                .containsExactlyInAnyOrder(BRASS_LAMP, FLOOR_LAMP);
        assertThat(ids(search(get("/product/search").param("q", "lamp").param("engine", "elasticsearch"))))
                .containsExactlyInAnyOrder(BRASS_LAMP, FLOOR_LAMP, LED_LAMPS);
    }

    @Test
    void pagesAreBoundedAndReportWhetherMoreExist() throws Exception {
        JsonNode first = search(get("/product/search").param("q", "lamp").param("engine", "elasticsearch")
                .param("sort", "price_desc").param("size", "2"));
        assertThat(ids(first)).containsExactly(FLOOR_LAMP, BRASS_LAMP);
        assertThat(first.get("hasMore").asBoolean()).isTrue();
        JsonNode second = search(get("/product/search").param("q", "lamp").param("engine", "elasticsearch")
                .param("sort", "price_desc").param("size", "2").param("page", "1"));
        assertThat(ids(second)).containsExactly(LED_LAMPS);
        assertThat(second.get("hasMore").asBoolean()).isFalse();

        mvc.perform(get("/product/search")).andExpect(status().isBadRequest());
        mvc.perform(get("/product/search").param("q", "lamp").param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/product/search").param("q", "lamp").param("size", "100").param("page", "10"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/product/search").param("q", "lamp").param("minPrice", "50").param("maxPrice", "10"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/product/search").param("q", "lamp").param("sort", "newest"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/product/search").param("q", "lamp").param("engine", "solr"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void backfillIndexesEveryProductAndEmptiesTheQueue() throws Exception {
        assertThat(indexer.documentCount()).isEqualTo(5);
        assertThat(jobs.pending()).isZero();
    }

    @Test
    void adminEditReachesSearchThroughTheJobTable() throws Exception {
        rename(DESK, "Walnut standing desk lamp");
        assertThat(jobs.pending()).isEqualTo(1);
        assertThat(ids(search(elasticsearch("standing")))).isEmpty(); // not drained yet

        assertThat(indexer.drainOnce()).isEqualTo(1);
        indexer.refresh();
        assertThat(ids(search(elasticsearch("standing")))).containsExactly(DESK);
        assertThat(jobs.pending()).isZero();
    }

    @Test
    void replayedJobsAndStaleSnapshotsNeverRegressADocument() throws Exception {
        List<Map<String, Object>> before = mysql.documents(List.of(BRASS_LAMP)); // version 1, a slow reader's copy
        rename(BRASS_LAMP, "Brass banker lamp");
        indexer.drainAll();

        // Replay: the same product queued again, as after a crash between the upsert and the job delete.
        jobs.enqueue(BRASS_LAMP);
        jobs.enqueue(BRASS_LAMP);
        assertThat(indexer.drainOnce()).isEqualTo(2);
        // A rebuild or a second backend that read the row before the edit, arriving last.
        indexer.upsert(before);
        indexer.refresh();

        assertThat(ids(search(elasticsearch("banker")))).containsExactly(BRASS_LAMP);
        assertThat(ids(search(elasticsearch("adjustable")))).containsExactly(BRASS_LAMP);
        Map<?, ?> doc = client.get(g -> g.index(props.elasticsearch().index()).id(String.valueOf(BRASS_LAMP)),
                Map.class).source();
        assertThat(doc.get("name")).isEqualTo("Brass banker lamp");
        assertThat(((Number) doc.get("search_version")).longValue()).isEqualTo(2);
    }

    @Test
    void queuedJobsSurviveAnOutageAndAnIndexerRestart() throws Exception {
        ES.getDockerClient().pauseContainerCmd(ES.getContainerId()).exec();
        try {
            rename(BOOKSHELF, "Oak ladder bookshelf");
            assertThat(indexer.drainOnce()).isZero();
            assertThat(jobs.pending()).isEqualTo(1);
            assertThat(data.count("SELECT attempts FROM search_index_jobs")).isEqualTo(1);
            // Search on Elasticsearch fails cleanly; the MySQL engine and the rest of the app keep working.
            mvc.perform(elasticsearch("ladder")).andExpect(status().isServiceUnavailable());
            assertThat(ids(search(get("/product/search").param("q", "ladder").param("engine", "mysql"))))
                    .containsExactly(BOOKSHELF);
        } finally {
            ES.getDockerClient().unpauseContainerCmd(ES.getContainerId()).exec();
        }

        // A fresh indexer (as after a backend restart) finds the job in MySQL and finishes it.
        ProductIndexer restarted = new ProductIndexer(client, jobs, mysql, props, new SimpleMeterRegistry());
        assertThat(restarted.drainAll()).isEqualTo(1);
        restarted.refresh();
        assertThat(ids(search(elasticsearch("ladder")))).containsExactly(BOOKSHELF);
        assertThat(jobs.pending()).isZero();
    }

    @Test
    void rebuildDuringAdminEditsConvergesToMySql() throws Exception {
        indexer.rebuild(); // empty index, five jobs queued
        rename(FLOOR_LAMP, "Arc floor lamp"); // committed while the rebuild is queued
        indexer.drainAll();
        indexer.refresh();

        assertThat(indexer.documentCount()).isEqualTo(5);
        assertThat(ids(search(elasticsearch("arc")))).containsExactly(FLOOR_LAMP);
        assertThat(ids(search(elasticsearch("linen")))).isEmpty();
    }

    private void rename(int productId, String name) throws Exception {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT image_url, description, price_cents, category_id FROM products WHERE id = ?", productId);
        Category category = categories.findById((Integer) row.get("category_id")).orElseThrow();
        ProductUpdateDto update = new ProductUpdateDto();
        update.setName(name);
        update.setImageURL((String) row.get("image_url"));
        update.setDescription((String) row.get("description"));
        update.setPrice(BigDecimal.valueOf(((Number) row.get("price_cents")).longValue(), 2));
        update.setCategoryId(category.getId());
        productService.updateProduct(productId, update, category);
    }

    private static MockHttpServletRequestBuilder elasticsearch(String q) {
        return get("/product/search").param("q", q).param("engine", "elasticsearch");
    }

    private JsonNode search(MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static List<Integer> ids(JsonNode page) {
        List<Integer> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("id").asInt()));
        return ids;
    }
}
