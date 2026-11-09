package ecommerce.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ecommerce.dto.product.ProductUpdateDto;
import ecommerce.exceptions.InsufficientStockException;
import ecommerce.model.Category;
import ecommerce.model.User;
import ecommerce.order.OrderService;
import ecommerce.payment.fake.FakePaymentGateway;
import ecommerce.repository.CategoryRepository;
import ecommerce.repository.UserRepository;
import ecommerce.service.ProductService;
import ecommerce.support.Contenders;
import ecommerce.support.MySqlIntegrationTest;
import ecommerce.support.TestData;

/**
 * Admin stock writes racing checkout reservations. Before Sprint 1, an edit saved the form's absolute stock
 * value, so an edit submitted during a reservation wrote the old stock back and the units sold twice.
 */
class AdminStockTest extends MySqlIntegrationTest {

    private static final int SKU = 1;
    private static final int FIRST_USER = 8001;

    @Autowired
    OrderService orderService;
    @Autowired
    ProductService productService;
    @Autowired
    UserRepository userRepository;
    @Autowired
    CategoryRepository categoryRepository;
    @Autowired
    FakePaymentGateway gateway;

    TestData data;

    @BeforeEach
    void seed() {
        gateway.reset();
        data = new TestData(jdbc).reset();
    }

    @Test
    void adminEditsDuringReservationsDoNotRestoreOldStock() throws Exception {
        int stock = 20;
        data.product(SKU, "Rain Jacket", "89.00", stock);
        List<Callable<String>> tasks = reservations(20);
        Category category = categoryRepository.findById(TestData.CATEGORY_ID).orElseThrow();
        for (int n = 0; n < 10; n++) {
            int edit = n;
            tasks.add(() -> {
                productService.updateProduct(SKU, update("Rain Jacket v" + edit, "89.00"), category);
                return "edited";
            });
        }

        List<String> outcomes = Contenders.race(tasks);

        assertThat(outcomes).doesNotContain("error").contains("edited");
        assertThat(outcomes.stream().filter("reserved"::equals).count()).isEqualTo(20);
        assertThat(data.stock(SKU)).isZero();
        assertThat(data.heldUnits(SKU)).isEqualTo(stock);
    }

    @Test
    void stockAdjustmentsComposeWithReservations() throws Exception {
        int stock = 20;
        data.product(SKU, "Yoga Mat", "30.00", stock);
        List<Callable<String>> tasks = reservations(30);
        for (int n = 0; n < 3; n++) {
            tasks.add(() -> {
                productService.adjustStock(SKU, 10);
                return "adjusted +10";
            });
        }
        for (int n = 0; n < 5; n++) {
            tasks.add(() -> {
                try {
                    productService.adjustStock(SKU, -1);
                    return "adjusted -1";
                } catch (InsufficientStockException ex) {
                    return "adjustment rejected";
                }
            });
        }

        List<String> outcomes = Contenders.race(tasks);

        assertThat(outcomes).doesNotContain("error");
        int available = data.stock(SKU);
        int held = data.heldUnits(SKU);
        int adjustments = 10 * (int) outcomes.stream().filter("adjusted +10"::equals).count()
                - (int) outcomes.stream().filter("adjusted -1"::equals).count();
        System.out.printf("adjustments race: available=%d held=%d adjustments=%+d outcomes=%s (hikari pool %d)%n",
                available, held, adjustments, summarize(outcomes), data.hikariMaxPoolSize());
        assertThat(available).isGreaterThanOrEqualTo(0);
        assertThat(available + held).as("initial + adjustments = available + held").isEqualTo(stock + adjustments);
        assertThat(held).isEqualTo((int) outcomes.stream().filter("reserved"::equals).count());
    }

    @Test
    void adjustmentThatWouldMakeStockNegativeIsRejected() throws Exception {
        data.product(SKU, "Teapot", "15.00", 2);

        assertThatThrownBy(() -> productService.adjustStock(SKU, -3))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(data.stock(SKU)).isEqualTo(2);
    }

    @Test
    void editChangesDescriptiveFieldsAndPriceButNeverStock() throws Exception {
        data.product(SKU, "Notebook", "4.99", 7);
        Category category = categoryRepository.findById(TestData.CATEGORY_ID).orElseThrow();

        productService.updateProduct(SKU, update("Notebook (lined)", "5.25"), category);

        assertThat(jdbc.queryForObject("SELECT name FROM products WHERE id = ?", String.class, SKU))
                .isEqualTo("Notebook (lined)");
        assertThat(jdbc.queryForObject("SELECT price_cents FROM products WHERE id = ?", Long.class, SKU))
                .isEqualTo(525L);
        assertThat(jdbc.queryForObject("SELECT price FROM products WHERE id = ?", Double.class, SKU))
                .isEqualTo(5.25);
        assertThat(data.stock(SKU)).isEqualTo(7);
    }

    private List<Callable<String>> reservations(int shoppers) {
        data.users(FIRST_USER, shoppers);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int n = 0; n < shoppers; n++) {
            data.cartLine(FIRST_USER + n, SKU, 1);
            User user = userRepository.findById(FIRST_USER + n).orElseThrow();
            tasks.add(() -> {
                try {
                    orderService.createSession(user, "admin-race-" + user.getId());
                    return "reserved";
                } catch (InsufficientStockException ex) {
                    return "out of stock";
                } catch (Exception ex) {
                    ex.printStackTrace();
                    return "error";
                }
            });
        }
        return tasks;
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

    private static String summarize(List<String> outcomes) {
        return outcomes.stream().collect(java.util.stream.Collectors.groupingBy(o -> o,
                java.util.TreeMap::new, java.util.stream.Collectors.counting())).toString();
    }
}
