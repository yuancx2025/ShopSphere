package ecommerce.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

import ecommerce.exceptions.InsufficientStockException;
import ecommerce.model.User;
import ecommerce.payment.fake.FakePaymentGateway;
import ecommerce.repository.UserRepository;
import ecommerce.support.Contenders;
import ecommerce.support.MySqlIntegrationTest;
import ecommerce.support.TestData;

/**
 * Roadmap target: zero oversells across three 100-attempt bursts. 100 distinct shoppers, one unit each, race
 * for a SKU with 20 units, with a healthy provider and no concurrent release.
 *
 * <p>Before Sprint 1 this accepted all 100 reservations and left stock at 9-10: the row lock was taken, but the
 * decrement was computed from the product entity the cart read had already loaded.
 */
class CheckoutBurstTest extends MySqlIntegrationTest {

    private static final int HOT_SKU = 1;
    private static final int STOCK = 20;
    private static final int SHOPPERS = 100;
    private static final int FIRST_USER = 5001;

    @Autowired
    OrderService orderService;
    @Autowired
    UserRepository userRepository;
    @Autowired
    FakePaymentGateway gateway;

    TestData data;

    @BeforeEach
    void seed() {
        gateway.reset();
        data = new TestData(jdbc).reset();
        data.product(HOT_SKU, "Hot SKU", "19.99", STOCK);
        data.users(FIRST_USER, SHOPPERS);
        for (int n = 0; n < SHOPPERS; n++) {
            data.cartLine(FIRST_USER + n, HOT_SKU, 1);
        }
    }

    @RepeatedTest(3)
    void exactlyTheAvailableUnitsAreReserved() throws Exception {
        List<Callable<String>> tasks = new ArrayList<>();
        for (int n = 0; n < SHOPPERS; n++) {
            User user = userRepository.findById(FIRST_USER + n).orElseThrow();
            tasks.add(() -> {
                try {
                    orderService.createSession(user, "burst-" + user.getId());
                    return "accepted";
                } catch (InsufficientStockException ex) {
                    return "rejected";
                } catch (Exception ex) {
                    return "error: " + ex;
                }
            });
        }
        List<String> outcomes = Contenders.race(tasks);

        long accepted = outcomes.stream().filter("accepted"::equals).count();
        long rejected = outcomes.stream().filter("rejected"::equals).count();
        int pending = data.count("SELECT COUNT(*) FROM orders WHERE status = 'PENDING'");
        int held = data.heldUnits(HOT_SKU);
        int stock = data.stock(HOT_SKU);
        System.out.printf("burst: accepted=%d rejected=%d pendingOrders=%d heldUnits=%d stock=%d sessions=%d "
                + "(hikari pool %d)%n", accepted, rejected, pending, held, stock, gateway.sessionsCreated(),
                data.hikariMaxPoolSize());

        assertThat(outcomes).allMatch(o -> o.equals("accepted") || o.equals("rejected"));
        assertThat(accepted).isEqualTo(STOCK);
        assertThat(rejected).isEqualTo(SHOPPERS - STOCK);
        assertThat(pending).isEqualTo(STOCK);
        assertThat(gateway.sessionsCreated()).isEqualTo(STOCK);
        assertThat(stock).isZero();
        // Inventory reconciles: initial stock = available + held by orders.
        assertThat(stock + held).isEqualTo(STOCK);
    }
}
