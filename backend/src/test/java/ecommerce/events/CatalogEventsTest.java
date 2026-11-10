package ecommerce.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static ecommerce.catalog.CatalogCacheConfig.PRODUCT_DETAIL;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import ecommerce.dto.product.ProductDto;
import ecommerce.dto.product.ProductUpdateDto;
import ecommerce.model.Category;
import ecommerce.repository.CategoryRepository;
import ecommerce.search.ProductIndexer;
import ecommerce.service.ProductService;
import ecommerce.support.MySqlIntegrationTest;
import ecommerce.support.SharedContainers;
import ecommerce.support.TestData;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Sprint 5 merge gate (PR 11): one admin edit, published from the outbox through RocketMQ, updates the search
 * document once and invalidates the cache entry once, also when the event is delivered twice; and a broker outage
 * leaves the outbox row pending until the broker is back.
 *
 * <p>The publisher's timer is off, so each test publishes by hand. The consumers are real push consumers on a
 * real broker, so the test waits for their ledger rows.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"events.enabled=true", "events.publisher.schedule=false",
        "events.publisher.send-timeout=1s", "events.publisher.retries=0",
        "search.elasticsearch.enabled=true", "search.indexer.schedule=false", "spring.elasticsearch.socket-timeout=2s",
        "spring.cache.type=redis"})
class CatalogEventsTest extends MySqlIntegrationTest {

    static final GenericContainer<?> ROCKETMQ = SharedContainers.rocketMq();

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("events.namesrv", SharedContainers::rocketMqNamesrv);
        registry.add("spring.elasticsearch.uris", () -> "http://" + SharedContainers.elasticsearch().getHttpHostAddress());
        registry.add("spring.data.redis.host", () -> SharedContainers.redis().getHost());
        registry.add("spring.data.redis.port", () -> SharedContainers.redis().getMappedPort(6379));
    }

    private static final String DETAIL_KEY = "shopsphere:catalog:v1:productDetail::";
    private static final String SEARCH = SearchIndexConsumer.GROUP;
    private static final String CACHE = CacheInvalidationConsumer.GROUP;
    private static final int LAMP = 1;
    private static final int DESK = 2;

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    OutboxPublisher publisher;
    @Autowired
    CatalogOutbox outbox;
    @Autowired
    EffectLedger ledger;
    @Autowired
    ProductIndexer indexer;
    @Autowired
    ElasticsearchClient elasticsearch;
    @Autowired
    ProductService productService;
    @Autowired
    CategoryRepository categories;
    @Autowired
    CacheManager caches;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    MeterRegistry meters;

    TestData data;

    @BeforeEach
    void seed() throws Exception {
        data = new TestData(jdbc).reset();
        redis.delete(redis.keys("shopsphere:catalog:*"));
        data.product(LAMP, "Brass desk lamp", "40.00", 5);
        data.product(DESK, "Walnut writing desk", "250.00", 3);
        // The backfill goes through the pipeline too: two outbox rows, two messages, two effects per group.
        indexer.rebuild();
        assertThat(publisher.publishAll()).isEqualTo(2);
        awaitTrue(() -> effects(SEARCH) == 2 && effects(CACHE) == 2, "backfill applied by both groups");
        indexer.refresh();
    }

    @Test
    void oneEditUpdatesTheSearchDocumentOnceAndInvalidatesTheCacheOnce() throws Exception {
        mvc.perform(get("/product/" + LAMP)).andExpect(status().isOk());
        ProductDto before = caches.getCache(PRODUCT_DETAIL).get(LAMP, ProductDto.class);
        assertThat(before).isNotNull();

        rename(LAMP, "Brass reading lamp");
        long eventId = latestEventId();
        // A reader that loaded the row before the commit writes the old body back after @CacheEvict ran.
        caches.getCache(PRODUCT_DETAIL).put(LAMP, before);
        assertThat(redis.hasKey(DETAIL_KEY + LAMP)).isTrue();

        double searchEffects = effectCounter(SEARCH);
        double cacheEffects = effectCounter(CACHE);
        assertThat(publisher.publishAll()).isEqualTo(1);
        assertThat(outbox.pending()).isZero();
        awaitTrue(() -> ledger.count(SEARCH, eventId) == 1 && ledger.count(CACHE, eventId) == 1, "edit applied");

        assertThat(redis.hasKey(DETAIL_KEY + LAMP)).as("stale entry evicted by the consumer").isFalse();
        indexer.refresh();
        assertThat(documentVersion(LAMP)).isEqualTo(2);
        assertThat(documentName(LAMP)).isEqualTo("Brass reading lamp");
        assertThat(effectCounter(SEARCH) - searchEffects).isEqualTo(1);
        assertThat(effectCounter(CACHE) - cacheEffects).isEqualTo(1);

        // The same event again (a publisher that crashed before marking it published): both groups skip it.
        double searchDuplicates = duplicateCounter(SEARCH);
        double cacheDuplicates = duplicateCounter(CACHE);
        publisher.send(ProductChanged.of(eventId, LAMP, ProductChange.UPDATED, Instant.now()));
        awaitTrue(() -> duplicateCounter(SEARCH) > searchDuplicates && duplicateCounter(CACHE) > cacheDuplicates,
                "duplicate seen by both groups");
        assertThat(ledger.count(SEARCH, eventId)).isEqualTo(1);
        assertThat(ledger.count(CACHE, eventId)).isEqualTo(1);
        assertThat(effectCounter(SEARCH) - searchEffects).isEqualTo(1);
        assertThat(effectCounter(CACHE) - cacheEffects).isEqualTo(1);
        assertThat(documentVersion(LAMP)).isEqualTo(2);
    }

    @Test
    void aBrokerOutageLeavesTheOutboxRowPendingUntilTheBrokerIsBack() throws Exception {
        ROCKETMQ.getDockerClient().pauseContainerCmd(ROCKETMQ.getContainerId()).exec();
        long eventId;
        try {
            productService.adjustStock(DESK, 4);
            eventId = latestEventId();
            assertThat(publisher.publishOnce()).isZero();
            assertThat(outbox.pending()).isEqualTo(1);
        } finally {
            ROCKETMQ.getDockerClient().unpauseContainerCmd(ROCKETMQ.getContainerId()).exec();
        }

        awaitTrue(() -> {
            publisher.publishAll();
            return outbox.pending() == 0;
        }, "outbox drained after the broker resumed");
        awaitTrue(() -> ledger.count(SEARCH, eventId) == 1 && ledger.count(CACHE, eventId) == 1,
                "stock event applied by both groups");
        assertThat(json.readTree(mvc.perform(get("/product/" + DESK)).andReturn().getResponse().getContentAsString())
                .get("stock").asInt()).isEqualTo(7);
        // Stock isn't indexed and doesn't bump the search version, so the document is untouched.
        assertThat(documentVersion(DESK)).isEqualTo(1);
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

    private long latestEventId() {
        return jdbc.queryForObject("SELECT MAX(id) FROM search_index_jobs", Long.class);
    }

    private int effects(String group) {
        return data.count("SELECT COUNT(*) FROM consumed_events WHERE consumer_group = ?", group);
    }

    private double effectCounter(String group) {
        return meters.counter("events.effects", "consumer", group).count();
    }

    private double duplicateCounter(String group) {
        return meters.counter("events.duplicates", "consumer", group).count();
    }

    @SuppressWarnings("rawtypes")
    private Map source(int productId) throws Exception {
        return elasticsearch.get(g -> g.index("products").id(String.valueOf(productId)), Map.class).source();
    }

    private long documentVersion(int productId) throws Exception {
        return elasticsearch.get(g -> g.index("products").id(String.valueOf(productId)), Map.class).version();
    }

    private String documentName(int productId) throws Exception {
        return (String) source(productId).get("name");
    }

    private static void awaitTrue(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }
}
