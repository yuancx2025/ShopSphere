package ecommerce.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ecommerce.dto.product.ProductUpdateDto;
import ecommerce.exceptions.InsufficientStockException;
import ecommerce.model.Category;
import ecommerce.model.User;
import ecommerce.order.OrderService;
import ecommerce.payment.fake.FakePaymentGateway;
import ecommerce.repository.CategoryRepository;
import ecommerce.repository.UserRepository;
import ecommerce.service.ProductService;
import ecommerce.support.MySqlIntegrationTest;
import ecommerce.support.SharedContainers;
import ecommerce.support.TestData;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Sprint 2 merge gate for the Redis cache: cached and uncached bodies match, admin writes evict the expected keys
 * after commit, checkout ignores the cached stock, and a Redis outage falls back to MySQL.
 *
 * <p>Tests change rows with plain SQL when they need the database and the cache to disagree. That is how a hit is
 * told apart from a fresh read.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.cache.type=redis", "catalog.cache.bypass-after-error=300ms"})
class CatalogCacheTest extends MySqlIntegrationTest {

    static final GenericContainer<?> REDIS = SharedContainers.redis();

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    private static final String DETAIL_KEY = CatalogCacheConfig.KEY_PREFIX + "productDetail::";
    private static final String PAGE_KEYS = CatalogCacheConfig.KEY_PREFIX + "catalogPage::*";
    private static final int LAMP = 1;
    private static final int DESK = 2;

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    ProductService productService;
    @Autowired
    OrderService orderService;
    @Autowired
    CategoryRepository categoryRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    FakePaymentGateway gateway;
    @Autowired
    MeterRegistry meters;

    TestData data;

    @BeforeEach
    void seed() {
        gateway.reset();
        data = new TestData(jdbc).reset();
        data.product(LAMP, "Lamp", "19.90", 5);
        data.product(DESK, "Desk", "120.00", 3);
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void aCachedDetailIsTheSameBodyAsTheDatabaseRead() throws Exception {
        String fromDatabase = body("/product/" + LAMP);
        assertThat(redis.hasKey(DETAIL_KEY + LAMP)).isTrue();
        assertThat(redis.getExpire(DETAIL_KEY + LAMP)).isBetween(1L, 60L);

        // The row changes behind the cache's back, so only a cache hit can still return the old body.
        jdbc.update("UPDATE products SET name = 'Renamed in SQL' WHERE id = ?", LAMP);
        assertThat(body("/product/" + LAMP)).isEqualTo(fromDatabase);
        assertThat(json.readTree(fromDatabase).get("price").decimalValue()).isEqualByComparingTo("19.90");
    }

    @Test
    void onlyFirstCatalogPagesAreCached() throws Exception {
        String firstPage = body("/product/page?categoryId=1&size=1");
        assertThat(redis.keys(PAGE_KEYS)).containsExactly(CatalogCacheConfig.KEY_PREFIX + "catalogPage::1:null:1");

        String cursor = json.readTree(firstPage).get("nextCursor").asText();
        JsonNode second = json.readTree(body("/product/page?categoryId=1&size=1&cursor=" + cursor));
        assertThat(second.get("items").get(0).get("id").asInt()).isEqualTo(DESK);
        assertThat(redis.keys(PAGE_KEYS)).hasSize(1);

        jdbc.update("UPDATE products SET name = 'Renamed in SQL' WHERE id = ?", LAMP);
        assertThat(body("/product/page?categoryId=1&size=1")).isEqualTo(firstPage);
    }

    @Test
    void adminWritesEvictTheProductAndEveryPage() throws Exception {
        body("/product/" + LAMP);
        body("/product/" + DESK);
        body("/product/page?categoryId=1");
        assertThat(redis.keys(PAGE_KEYS)).hasSize(1);

        Category category = categoryRepository.findById(TestData.CATEGORY_ID).orElseThrow();
        productService.updateProduct(LAMP, update("Desk Lamp", "24.50"), category);

        assertThat(redis.hasKey(DETAIL_KEY + LAMP)).isFalse();
        assertThat(redis.hasKey(DETAIL_KEY + DESK)).isTrue();
        assertThat(redis.keys(PAGE_KEYS)).isEmpty();
        JsonNode lamp = json.readTree(body("/product/" + LAMP));
        assertThat(lamp.get("name").asText()).isEqualTo("Desk Lamp");
        assertThat(lamp.get("price").decimalValue()).isEqualByComparingTo("24.50");
        assertThat(json.readTree(body("/product/page?categoryId=1")).get("items").get(0).get("name").asText())
                .isEqualTo("Desk Lamp");

        productService.adjustStock(LAMP, 4);
        assertThat(json.readTree(body("/product/" + LAMP)).get("stock").asInt()).isEqualTo(9);
        assertThat(json.readTree(body("/product/page?categoryId=1")).get("items").get(0).get("stock").asInt())
                .isEqualTo(9);
    }

    @Test
    void checkoutReservesAgainstMySqlNotTheCachedStock() throws Exception {
        assertThat(json.readTree(body("/product/" + LAMP)).get("stock").asInt()).isEqualTo(5);
        // Units sell outside the admin API, which never evicts. The cached copy keeps showing 5 until its TTL ends.
        jdbc.update("UPDATE products SET stock = 0 WHERE id = ?", LAMP);
        assertThat(json.readTree(body("/product/" + LAMP)).get("stock").asInt()).isEqualTo(5);

        data.users(9001, 1);
        data.cartLine(9001, LAMP, 1);
        User shopper = userRepository.findById(9001).orElseThrow();
        assertThatThrownBy(() -> orderService.createSession(shopper, "cache-test"))
                .isInstanceOf(InsufficientStockException.class);
        assertThat(data.stock(LAMP)).isZero();
    }

    @Test
    void aRedisOutageFallsBackToMySqlAndRecovers() throws Exception {
        double errorsBefore = getErrors();
        double bypassedBefore = bypassed();
        // A paused container keeps the TCP connection open and never answers: the worst case, a hung Redis.
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            long pausedAt = System.nanoTime();
            long started = pausedAt;
            assertThat(json.readTree(body("/product/" + DESK)).get("id").asInt()).isEqualTo(DESK);
            long firstMs = (System.nanoTime() - started) / 1_000_000;
            started = System.nanoTime();
            for (int n = 0; n < 20; n++) {
                assertThat(json.readTree(body("/product/" + DESK)).get("id").asInt()).isEqualTo(DESK);
            }
            long nextMs = (System.nanoTime() - started) / 20 / 1_000_000;
            System.out.printf("redis hang: first request %d ms (one command timeout), then %d ms each on MySQL; "
                    + "%.0f Redis errors, %.0f bypassed%n", firstMs, nextMs, getErrors() - errorsBefore,
                    bypassed() - bypassedBefore);
            // The first request pays the 100 ms command timeout, then the bypass window skips Redis entirely.
            assertThat(firstMs).isLessThan(1_000);
            assertThat(nextMs).isLessThan(50);
            // At most one probe of the hung Redis per 300 ms bypass window, not one per request. On a busy host the
            // 21 requests can outlast a window, so the count is bounded by elapsed windows rather than fixed at 1.
            long windows = 1 + (System.nanoTime() - pausedAt) / 300_000_000L;
            assertThat(getErrors() - errorsBefore).isBetween(1.0, (double) windows);
            assertThat(bypassed()).isGreaterThan(bypassedBefore);
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }

        Thread.sleep(400); // let the 300 ms bypass window close
        body("/product/" + DESK);
        assertThat(redis.hasKey(DETAIL_KEY + DESK)).isTrue();
        jdbc.update("UPDATE products SET name = 'Renamed in SQL' WHERE id = ?", DESK);
        assertThat(json.readTree(body("/product/" + DESK)).get("name").asText()).isEqualTo("Desk");
    }

    private String body(String path) throws Exception {
        return mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private double getErrors() {
        return meters.find("catalog.cache.errors").tag("operation", "get").counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    private double bypassed() {
        return meters.find("catalog.cache.bypassed").counters().stream().mapToDouble(Counter::count).sum();
    }

    private static ProductUpdateDto update(String name, String price) {
        ProductUpdateDto dto = new ProductUpdateDto();
        dto.setName(name);
        dto.setImageURL("https://images.shopsphere.test/p1.jpg");
        dto.setDescription("Edited by an admin");
        dto.setPrice(new BigDecimal(price));
        dto.setCategoryId(TestData.CATEGORY_ID);
        return dto;
    }
}
