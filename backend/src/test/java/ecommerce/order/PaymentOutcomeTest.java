package ecommerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ecommerce.exceptions.CustomException;
import ecommerce.model.User;
import ecommerce.order.model.OrderStatus;
import ecommerce.payment.dto.CheckoutSessionResponseDto;
import ecommerce.payment.fake.FakePaymentGateway;
import ecommerce.payment.fake.FakePaymentGateway.CreateBehavior;
import ecommerce.repository.UserRepository;
import ecommerce.support.Contenders;
import ecommerce.support.MySqlIntegrationTest;
import ecommerce.support.TestData;

/**
 * Payment outcomes checked at the database boundary: terminal status, stock released exactly once, and the
 * inventory invariant (initial stock = available + held by orders) after every path.
 */
class PaymentOutcomeTest extends MySqlIntegrationTest {

    private static final int SKU = 1;
    private static final int STOCK = 5;
    private static final int USER = 6001;
    private static final int OTHER_USER = 6002;

    @Autowired
    OrderService orderService;
    @Autowired
    UserRepository userRepository;
    @Autowired
    FakePaymentGateway gateway;

    TestData data;
    User user;

    @BeforeEach
    void seed() {
        gateway.reset();
        data = new TestData(jdbc).reset();
        data.product(SKU, "Desk Lamp", "19.99", STOCK);
        data.users(USER, 2);
        data.cartLine(USER, SKU, 2);
        data.cartLine(OTHER_USER, SKU, 1);
        user = userRepository.findById(USER).orElseThrow();
    }

    @Test
    void paidSessionMarksOrderPaidAndClearsCart() throws Exception {
        String sessionId = checkout("happy-path-1").getSessionId();
        gateway.pay(sessionId);

        orderService.fulfillCheckout(sessionId);

        assertThat(statusOf(sessionId)).isEqualTo("PAID");
        assertThat(data.cartLines(USER)).isZero();
        assertInventory(STOCK - 2, 2);
    }

    @Test
    void duplicateAndConcurrentConfirmationsApplyPaymentOnce() throws Exception {
        String sessionId = checkout("dup-confirm-1").getSessionId();
        gateway.pay(sessionId);
        List<Callable<String>> confirmations = new ArrayList<>();
        for (int n = 0; n < 5; n++) {
            confirmations.add(() -> orderService.fulfillCheckout(sessionId).getStatus().name());
            confirmations.add(() -> orderService.reconcileCheckout(sessionId, user).getStatus().name());
        }

        List<String> seen = Contenders.race(confirmations);

        assertThat(seen).containsOnly("PAID");
        assertThat(statusOf(sessionId)).isEqualTo("PAID");
        assertInventory(STOCK - 2, 2);
    }

    @Test
    void amountMismatchNeedsReviewAndKeepsTheReservation() throws Exception {
        String sessionId = checkout("amount-mismatch").getSessionId();
        gateway.pay(sessionId);
        gateway.tamper(sessionId, 1L, null, null);

        orderService.fulfillCheckout(sessionId);

        assertReview(sessionId, "AMOUNT_MISMATCH");
        assertThat(data.cartLines(USER)).isEqualTo(1);
        assertInventory(STOCK - 2, 2);
    }

    @Test
    void currencyMismatchNeedsReview() throws Exception {
        String sessionId = checkout("currency-mismatch").getSessionId();
        gateway.pay(sessionId);
        gateway.tamper(sessionId, null, "eur", null);

        orderService.fulfillCheckout(sessionId);

        assertReview(sessionId, "CURRENCY_MISMATCH");
    }

    @Test
    void sessionCarryingAnotherOrderIdNeedsReview() throws Exception {
        String sessionId = checkout("order-mismatch").getSessionId();
        User other = userRepository.findById(OTHER_USER).orElseThrow();
        orderService.createSession(other, "other-order-1");
        int otherOrderId = data.count("SELECT id FROM orders WHERE user_id = ?", OTHER_USER);
        gateway.pay(sessionId);
        gateway.tamper(sessionId, null, null, otherOrderId);

        orderService.fulfillCheckout(sessionId);

        assertReview(sessionId, "ORDER_MISMATCH");
        assertThat(data.orderStatus(otherOrderId)).isEqualTo("PENDING");
    }

    @Test
    void repeatedExpiryEventsReleaseStockExactlyOnce() throws Exception {
        String sessionId = checkout("expire-once").getSessionId();
        List<Callable<Boolean>> expiries = new ArrayList<>();
        for (int n = 0; n < 6; n++) {
            expiries.add(() -> {
                orderService.releaseCheckout(sessionId, OrderStatus.CANCELLED);
                return true;
            });
        }

        Contenders.race(expiries);

        assertThat(statusOf(sessionId)).isEqualTo("CANCELLED");
        assertInventory(STOCK, 0);
    }

    @Test
    void paymentAfterReleaseNeedsReviewAndDoesNotTakeStockAgain() throws Exception {
        String sessionId = checkout("late-payment").getSessionId();
        orderService.releaseCheckout(sessionId, OrderStatus.CANCELLED);
        gateway.payLate(sessionId);

        orderService.fulfillCheckout(sessionId);

        assertReview(sessionId, "PAID_AFTER_RELEASE");
        assertInventory(STOCK, 0);
    }

    @RepeatedTest(5)
    void expiryRacingPaymentNeverLeavesAPaidOrderWithoutStock() throws Exception {
        String sessionId = checkout("race-expiry-" + System.nanoTime()).getSessionId();
        gateway.payLate(sessionId);
        List<Callable<Boolean>> racers = List.of(
                () -> {
                    orderService.releaseCheckout(sessionId, OrderStatus.CANCELLED);
                    return true;
                },
                () -> {
                    orderService.fulfillCheckout(sessionId);
                    return true;
                });

        Contenders.race(racers);

        String status = statusOf(sessionId);
        if (status.equals("PAID")) {
            assertInventory(STOCK - 2, 2);
        } else {
            assertReview(sessionId, "PAID_AFTER_RELEASE");
            assertInventory(STOCK, 0);
        }
    }

    @Test
    void providerRejectionReleasesStockImmediately() {
        gateway.script(CreateBehavior.REJECT);

        assertThatThrownBy(() -> orderService.createSession(user, "rejected-1")).isInstanceOf(CustomException.class);

        assertThat(latestOrderStatus()).isEqualTo("CANCELLED");
        assertInventory(STOCK, 0);
    }

    @Test
    void orderTotalIsExactAndMatchesWhatTheProviderCollects() throws Exception {
        data.product(2, "Sticker", "0.10", 10);
        data.product(3, "Pencil", "0.20", 10);
        jdbc.update("UPDATE cart SET quantity = 3 WHERE user_id = ?", USER);
        data.cartLine(USER, 2, 1);
        data.cartLine(USER, 3, 2);

        String sessionId = checkout("exact-amounts").getSessionId();

        long total = jdbc.queryForObject("SELECT total_cents FROM orders WHERE session_id = ?", Long.class, sessionId);
        long itemSum = jdbc.queryForObject("SELECT SUM(unit_price_cents * quantity) FROM orderitems oi "
                + "JOIN orders o ON o.id = oi.order_id WHERE o.session_id = ?", Long.class, sessionId);
        assertThat(total).isEqualTo(3 * 1999 + 10 + 2 * 20).isEqualTo(itemSum);
        assertThat(gateway.retrieveSession(sessionId).getAmountTotalCents()).isEqualTo(total);

        gateway.pay(sessionId);
        orderService.fulfillCheckout(sessionId);
        assertThat(statusOf(sessionId)).isEqualTo("PAID");
    }

    // ------------------------------------------------------------------ helpers

    private CheckoutSessionResponseDto checkout(String key) throws Exception {
        return orderService.createSession(user, key);
    }

    private String statusOf(String sessionId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE session_id = ?", String.class, sessionId);
    }

    private String latestOrderStatus() {
        return jdbc.queryForObject("SELECT status FROM orders WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, USER);
    }

    private void assertReview(String sessionId, String reason) {
        assertThat(statusOf(sessionId)).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc.queryForObject("SELECT review_reason FROM orders WHERE session_id = ?", String.class,
                sessionId)).isEqualTo(reason);
    }

    private void assertInventory(int expectedAvailable, int expectedHeld) {
        assertThat(data.stock(SKU)).as("available stock").isEqualTo(expectedAvailable);
        assertThat(data.heldUnits(SKU)).as("units held by orders").isEqualTo(expectedHeld);
        assertThat(data.stock(SKU) + data.heldUnits(SKU)).as("initial = available + held").isEqualTo(STOCK);
    }
}
