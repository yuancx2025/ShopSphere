package ecommerce.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import ecommerce.dto.product.ProductCardDto;
import ecommerce.dto.product.ProductPageDto;
import ecommerce.support.MySqlIntegrationTest;
import ecommerce.support.TestData;

/** Keyset pagination: complete, duplicate-free, stably ordered (with id tie-breaks), bounded, and validated. */
@AutoConfigureMockMvc
class CatalogPageTest extends MySqlIntegrationTest {

    private static final int OTHER_CATEGORY = 2;
    private static final int IN_CATEGORY = 57;

    @Autowired
    CatalogService catalogService;
    @Autowired
    MockMvc mvc;

    record Seeded(int id, int category, long priceCents) {
    }

    List<Seeded> seeded = new ArrayList<>();

    @BeforeEach
    void seed() {
        TestData data = new TestData(jdbc).reset();
        data.category(OTHER_CATEGORY, "Other");
        seeded.clear();
        // Only seven distinct prices, so many rows tie on price and the id tie-breaker matters.
        String[] prices = {"9.99", "4.50", "19.99", "4.50", "0.99", "9.99", "100.00"};
        for (int id = 1; id <= IN_CATEGORY + 23; id++) {
            int category = id <= IN_CATEGORY ? TestData.CATEGORY_ID : OTHER_CATEGORY;
            String price = prices[id % prices.length];
            data.product(id, category, "Product " + id, price, id % 4);
            seeded.add(new Seeded(id, category, new java.math.BigDecimal(price).movePointRight(2).longValueExact()));
        }
    }

    @Test
    void walkingEveryPageReturnsEachProductOnceInSortOrder() {
        List<Integer> byPriceAsc = expected(Comparator.comparingLong(Seeded::priceCents).thenComparingInt(Seeded::id));
        List<Integer> byId = expected(Comparator.comparingInt(Seeded::id));

        assertThat(walk("price_asc", 10)).isEqualTo(byPriceAsc);
        assertThat(walk("id", 20)).isEqualTo(byId);
    }

    @Test
    void pageSizeIsCapped() {
        ProductPageDto page = catalogService.page(null, null, 10_000, null);
        assertThat(page.size()).isEqualTo(CatalogService.MAX_PAGE_SIZE);
        assertThat(page.items()).hasSize(80);
    }

    @Test
    void httpContractAndValidation() throws Exception {
        mvc.perform(get("/product/page").param("categoryId", "1").param("sort", "price_asc").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.items[0].id").value(4))
                .andExpect(jsonPath("$.items[0].price").value(0.99))
                .andExpect(jsonPath("$.items[0].imageURL").value(notNullValue()))
                .andExpect(jsonPath("$.items[0].categoryId").value(1))
                .andExpect(jsonPath("$.items[0].priceCents").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").value(notNullValue()))
                .andExpect(jsonPath("$.size").value(5));
        mvc.perform(get("/product/page").param("categoryId", "2").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(23)))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));

        mvc.perform(get("/product/page").param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/product/page").param("sort", "price_asc")).andExpect(status().isBadRequest());
        mvc.perform(get("/product/page").param("sort", "newest")).andExpect(status().isBadRequest());
        mvc.perform(get("/product/page").param("cursor", "not-a-cursor")).andExpect(status().isBadRequest());
        String idCursor = catalogService.page(1, "id", 5, null).nextCursor();
        mvc.perform(get("/product/page").param("categoryId", "1").param("sort", "price_asc").param("cursor", idCursor))
                .andExpect(status().isBadRequest());
    }

    @Test
    void legacyEndpointsKeepTheirContract() throws Exception {
        mvc.perform(get("/product/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(80)))
                .andExpect(jsonPath("$[0].price").value(4.5))
                .andExpect(jsonPath("$[0].stock").value(1));
        mvc.perform(get("/product/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.price").value(19.99))
                .andExpect(jsonPath("$.categoryId").value(1));
    }

    private List<Integer> walk(String sort, int size) {
        List<Integer> ids = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            ProductPageDto page = catalogService.page(TestData.CATEGORY_ID, sort, size, cursor);
            assertThat(page.items().size()).isLessThanOrEqualTo(size);
            page.items().stream().map(ProductCardDto::id).forEach(ids::add);
            cursor = page.nextCursor();
            assertThat(++pages).isLessThan(100);
        } while (cursor != null);
        return ids;
    }

    private List<Integer> expected(Comparator<Seeded> order) {
        return seeded.stream().filter(s -> s.category() == TestData.CATEGORY_ID).sorted(order).map(Seeded::id).toList();
    }
}
