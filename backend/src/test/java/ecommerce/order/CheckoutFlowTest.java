package ecommerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import ecommerce.exceptions.CustomException;
import ecommerce.exceptions.InsufficientStockException;
import ecommerce.model.Cart;
import ecommerce.model.Category;
import ecommerce.model.Product;
import ecommerce.model.User;
import ecommerce.order.model.Order;
import ecommerce.order.model.OrderStatus;
import ecommerce.order.repository.OrderRepository;
import ecommerce.payment.FakePaymentGateway;
import ecommerce.payment.PaymentGateway;
import ecommerce.payment.dto.CheckoutSessionResponseDto;
import ecommerce.payment.stripe.StripeEventService;
import ecommerce.payment.stripe.persistence.ProcessedStripeEventRepository;
import ecommerce.repository.CartRepository;
import ecommerce.repository.CategoryRepository;
import ecommerce.repository.ProductRepository;
import ecommerce.repository.UserRepository;

/**
 * Covers the checkout state machine against a real Postgres, driving the provider through
 * {@link FakePaymentGateway} so every outcome (settled, expired, mismatched, timed out) is
 * reproducible.
 */
@SpringBootTest(properties = {
        // The sweeper is exercised explicitly; a background thread would race these assertions.
        "payments.reconciliation.enabled=false"
})
@Testcontainers
@Import(CheckoutFlowTest.FakeGatewayConfig.class)
class CheckoutFlowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @TestConfiguration
    static class FakeGatewayConfig {
        @Bean
        @Primary
        PaymentGateway fakePaymentGateway() {
            return new FakePaymentGateway();
        }
    }

    @Autowired
    private OrderService orderService;
    @Autowired
    private StripeEventService stripeEventService;
    @Autowired
    private PaymentGateway paymentGateway;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CartRepository cartRepository;
    @Autowired
    private ProcessedStripeEventRepository processedStripeEventRepository;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);

    private FakePaymentGateway gateway;
    private Category category;

    @AfterAll
    static void shutdownExecutor() {
        EXECUTOR.shutdownNow();
    }

    @BeforeEach
    void resetState() {
        gateway = (FakePaymentGateway) paymentGateway;
        gateway.reset();
        processedStripeEventRepository.deleteAll();
        orderRepository.deleteAll();
        cartRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
        categoryRepository.deleteAll();
        category = categoryRepository.save(new Category("Test", "Test category", "https://example.test/c.png"));
    }

    // ---------------------------------------------------------------------
    // Happy path and idempotency
    // ---------------------------------------------------------------------

    @Test
    void webhookFulfilmentMarksOrderPaidAndClearsCart() throws Exception {
        User user = givenUser("buyer@example.test");
        Product product = givenProduct("Keyboard", 25.00, 10);
        givenCartItem(user, product, 2);

        CheckoutSessionResponseDto checkout = orderService.createSession(user);

        assertThat(stockOf(product)).isEqualTo(8);
        assertThat(statusOfSession(checkout.getSessionId())).isEqualTo(OrderStatus.PENDING);

        gateway.settle(checkout.getSessionId());
        orderService.fulfillCheckout(checkout.getSessionId());

        assertThat(statusOfSession(checkout.getSessionId())).isEqualTo(OrderStatus.PAID);
        assertThat(cartRepository.findAllByUserOrderByCreatedDateDesc(user)).isEmpty();
        assertThat(stockOf(product)).isEqualTo(8);
    }

    @Test
    void duplicateFulfilmentTransitionsOnceAndDoesNotDoubleDeductStock() throws Exception {
        User user = givenUser("dupe@example.test");
        Product product = givenProduct("Mouse", 10.00, 5);
        givenCartItem(user, product, 1);

        CheckoutSessionResponseDto checkout = orderService.createSession(user);
        gateway.settle(checkout.getSessionId());

        orderService.fulfillCheckout(checkout.getSessionId());
        orderService.fulfillCheckout(checkout.getSessionId());
        orderService.fulfillCheckout(checkout.getSessionId());

        assertThat(statusOfSession(checkout.getSessionId())).isEqualTo(OrderStatus.PAID);
        // Stock was decremented once at reservation and never restored or re-deducted.
        assertThat(stockOf(product)).isEqualTo(4);
        assertThat(orderRepository.findAll()).hasSize(1);
    }

    @Test
    void eventInboxLetsOnlyOneDeliveryOfAnEventThrough() {
        assertThat(stripeEventService.claim("evt_duplicate")).isTrue();
        assertThat(stripeEventService.claim("evt_duplicate")).isFalse();

        stripeEventService.complete("evt_duplicate");
        assertThat(stripeEventService.claim("evt_duplicate")).isFalse();
    }

    @Test
    void releasingAClaimLetsTheProviderRetry() {
        assertThat(stripeEventService.claim("evt_transient")).isTrue();
        // Handler blew up, so the claim is dropped and the retry must be able to re-enter.
        stripeEventService.release("evt_transient");

        assertThat(stripeEventService.claim("evt_transient")).isTrue();
    }

    // ---------------------------------------------------------------------
    // Amount and currency verification
    // ---------------------------------------------------------------------

    @Test
    void providerReportingADifferentAmountGoesToReviewInsteadOfPaid() throws Exception {
        User user = givenUser("tamper@example.test");
        Product product = givenProduct("Monitor", 100.00, 3);
        givenCartItem(user, product, 1);

        CheckoutSessionResponseDto checkout = orderService.createSession(user);
        gateway.settle(checkout.getSessionId());
        gateway.reportAmount(checkout.getSessionId(), 1L); // paid one cent for a $100 order

        orderService.fulfillCheckout(checkout.getSessionId());

        assertThat(statusOfSession(checkout.getSessionId())).isEqualTo(OrderStatus.PAYMENT_REVIEW);
        // Stock stays reserved: the provider claims it took money, so releasing would be unsafe.
        assertThat(stockOf(product)).isEqualTo(2);
        assertThat(cartRepository.findAllByUserOrderByCreatedDateDesc(user)).isNotEmpty();
    }

    @Test
    void providerReportingADifferentCurrencyGoesToReview() throws Exception {
        User user = givenUser("currency@example.test");
        Product product = givenProduct("Webcam", 40.00, 3);
        givenCartItem(user, product, 1);

        CheckoutSessionResponseDto checkout = orderService.createSession(user);
        gateway.settle(checkout.getSessionId());
        gateway.reportCurrency(checkout.getSessionId(), "jpy");

        orderService.fulfillCheckout(checkout.getSessionId());

        assertThat(statusOfSession(checkout.getSessionId())).isEqualTo(OrderStatus.PAYMENT_REVIEW);
    }

    @Test
    void aMissingProviderAmountIsNotTreatedAsAMatch() throws Exception {
        User user = givenUser("noamount@example.test");
        Product product = givenProduct("Cable", 5.00, 3);
        givenCartItem(user, product, 2);

        CheckoutSessionResponseDto checkout = orderService.createSession(user);
        gateway.settle(checkout.getSessionId());
        gateway.reportAmount(checkout.getSessionId(), null);

        orderService.fulfillCheckout(checkout.getSessionId());

        assertThat(statusOfSession(checkout.getSessionId())).isEqualTo(OrderStatus.PAYMENT_REVIEW);
    }

    // ---------------------------------------------------------------------
    // Webhook and success-page ordering
    // ---------------------------------------------------------------------

    @Test
    void reconcileAfterWebhookLeavesTheOrderPaidExactlyOnce() throws Exception {
        User user = givenUser("order1@example.test");
        Product product = givenProduct("Desk", 200.00, 4);
        givenCartItem(user, product, 1);

        CheckoutSessionResponseDto checkout = orderService.createSession(user);
        gateway.settle(checkout.getSessionId());

        orderService.fulfillCheckout(checkout.getSessionId());
        orderService.reconcileCheckout(checkout.getSessionId(), user);

        assertThat(statusOfSession(checkout.getSessionId())).isEqualTo(OrderStatus.PAID);
        assertThat(stockOf(product)).isEqualTo(3);
    }

    @Test
    void webhookAfterReconcileLeavesTheOrderPaidExactlyOnce() throws Exception {
        User user = givenUser("order2@example.test");
        Product product = givenProduct("Chair", 150.00, 4);
        givenCartItem(user, product, 1);

        CheckoutSessionResponseDto checkout = orderService.createSession(user);
        gateway.settle(checkout.getSessionId());

        // Customer lands on the success page before the webhook arrives.
        orderService.reconcileCheckout(checkout.getSessionId(), user);
        orderService.fulfillCheckout(checkout.getSessionId());

        assertThat(statusOfSession(checkout.getSessionId())).isEqualTo(OrderStatus.PAID);
        assertThat(stockOf(product)).isEqualTo(3);
    }

    @Test
    void aDelayedPaymentIsNotFulfilledWhileStillProcessing() throws Exception {
        User user = givenUser("delayed@example.test");
        Product product = givenProduct("Lamp", 30.00, 4);
        givenCartItem(user, product, 1);

        CheckoutSessionResponseDto checkout = orderService.createSession(user);
        gateway.processing(checkout.getSessionId());

        orderService.fulfillCheckout(checkout.getSessionId());

        assertThat(statusOfSession(checkout.getSessionId())).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void anUnknownSessionThrowsSoTheProviderRetriesInsteadOfConsumingTheEvent() {
        assertThatThrownBy(() -> orderService.fulfillCheckout("cs_test_does_not_exist"))
                .isInstanceOf(RuntimeException.class);
    }

    // ---------------------------------------------------------------------
    // Release paths
    // ---------------------------------------------------------------------

    @Test
    void expiredSessionRestoresExactStock() throws Exception {
        User user = givenUser("expired@example.test");
        Product product = givenProduct("Headset", 60.00, 7);
        givenCartItem(user, product, 3);

        CheckoutSessionResponseDto checkout = orderService.createSession(user);
        assertThat(stockOf(product)).isEqualTo(4);

        orderService.releaseCheckout(checkout.getSessionId(), OrderStatus.CANCELLED);

        assertThat(statusOfSession(checkout.getSessionId())).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stockOf(product)).isEqualTo(7);
    }

    @Test
    void providerFailureDuringCreationReleasesTheReservation() throws Exception {
        User user = givenUser("outage@example.test");
        Product product = givenProduct("Speaker", 80.00, 6);
        givenCartItem(user, product, 2);

        gateway.failNextCreation();

        assertThatThrownBy(() -> orderService.createSession(user)).isInstanceOf(CustomException.class);

        assertThat(stockOf(product)).isEqualTo(6);
        assertThat(orderRepository.findAll())
                .allSatisfy(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED));
    }

    @Test
    void startingASecondCheckoutCancelsTheFirstAndKeepsStockBalanced() throws Exception {
        User user = givenUser("retry@example.test");
        Product product = givenProduct("Tablet", 300.00, 5);
        givenCartItem(user, product, 2);

        CheckoutSessionResponseDto first = orderService.createSession(user);
        assertThat(stockOf(product)).isEqualTo(3);

        CheckoutSessionResponseDto second = orderService.createSession(user);

        assertThat(statusOfSession(first.getSessionId())).isEqualTo(OrderStatus.CANCELLED);
        assertThat(statusOfSession(second.getSessionId())).isEqualTo(OrderStatus.PENDING);
        // The first reservation was returned before the second was taken.
        assertThat(stockOf(product)).isEqualTo(3);
    }

    // ---------------------------------------------------------------------
    // Reconciliation sweeper
    // ---------------------------------------------------------------------

    @Test
    void reconcilingAStaleExpiredOrderReleasesStock() throws Exception {
        User user = givenUser("stale@example.test");
        Product product = givenProduct("Router", 90.00, 5);
        givenCartItem(user, product, 2);

        CheckoutSessionResponseDto checkout = orderService.createSession(user);
        assertThat(stockOf(product)).isEqualTo(3);

        // The webhook never arrived and the provider session has since lapsed.
        gateway.expire(checkout.getSessionId());
        Order order = orderRepository.findBySessionIdWithItems(checkout.getSessionId()).orElseThrow();

        assertThat(orderService.reconcileStaleOrder(order.getId())).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stockOf(product)).isEqualTo(5);
    }

    @Test
    void reconcilingAStaleButActuallyPaidOrderFulfilsIt() throws Exception {
        User user = givenUser("lostwebhook@example.test");
        Product product = givenProduct("Dock", 120.00, 5);
        givenCartItem(user, product, 1);

        CheckoutSessionResponseDto checkout = orderService.createSession(user);
        gateway.settle(checkout.getSessionId());
        Order order = orderRepository.findBySessionIdWithItems(checkout.getSessionId()).orElseThrow();

        assertThat(orderService.reconcileStaleOrder(order.getId())).isEqualTo(OrderStatus.PAID);
        assertThat(stockOf(product)).isEqualTo(4);
    }

    @Test
    void reconcilingAnOrderStillAwaitingTheCustomerLeavesItAlone() throws Exception {
        User user = givenUser("inflight@example.test");
        Product product = givenProduct("Stand", 45.00, 5);
        givenCartItem(user, product, 1);

        CheckoutSessionResponseDto checkout = orderService.createSession(user);
        Order order = orderRepository.findBySessionIdWithItems(checkout.getSessionId()).orElseThrow();

        assertThat(orderService.reconcileStaleOrder(order.getId())).isNull();
        assertThat(statusOfSession(checkout.getSessionId())).isEqualTo(OrderStatus.PENDING);
        assertThat(stockOf(product)).isEqualTo(4);
    }

    @Test
    void reconcilingAnOrderThatNeverGotASessionReleasesStock() throws Exception {
        User user = givenUser("nosession@example.test");
        Product product = givenProduct("Hub", 20.00, 5);
        givenCartItem(user, product, 2);

        CheckoutSessionResponseDto checkout = orderService.createSession(user);
        Order order = orderRepository.findBySessionIdWithItems(checkout.getSessionId()).orElseThrow();
        // Simulate attachSessionId having failed after the provider created the session.
        order.setSessionId(null);
        orderRepository.save(order);

        assertThat(orderService.reconcileStaleOrder(order.getId())).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stockOf(product)).isEqualTo(5);
    }

    // ---------------------------------------------------------------------
    // Concurrency
    // ---------------------------------------------------------------------

    @Test
    void concurrentCheckoutsForTheLastUnitCannotOversell() throws Exception {
        Product product = givenProduct("Limited Edition", 500.00, 1);
        User first = givenUser("racer1@example.test");
        User second = givenUser("racer2@example.test");
        givenCartItem(first, product, 1);
        givenCartItem(second, product, 1);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CyclicBarrier startLine = new CyclicBarrier(2);

        Future<Void> a = EXECUTOR.submit(attempt(first, startLine, succeeded, rejected));
        Future<Void> b = EXECUTOR.submit(attempt(second, startLine, succeeded, rejected));
        a.get(30, TimeUnit.SECONDS);
        b.get(30, TimeUnit.SECONDS);

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
        assertThat(stockOf(product)).isZero();

        List<Order> pending = orderRepository.findAll().stream()
                .filter(order -> order.getStatus() == OrderStatus.PENDING)
                .toList();
        assertThat(pending).hasSize(1);
    }

    private Callable<Void> attempt(
            User user, CyclicBarrier startLine, AtomicInteger succeeded, AtomicInteger rejected) {
        return () -> {
            startLine.await(30, TimeUnit.SECONDS);
            try {
                orderService.createSession(user);
                succeeded.incrementAndGet();
            } catch (InsufficientStockException | CustomException ex) {
                rejected.incrementAndGet();
            }
            return null;
        };
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    private User givenUser(String email) {
        User user = new User("Test", "Buyer", email, "irrelevant");
        return userRepository.save(user);
    }

    private Product givenProduct(String name, double price, int stock) {
        return productRepository.save(
                new Product(name, "https://example.test/p.png", price, "A product", category, stock));
    }

    private void givenCartItem(User user, Product product, int quantity) {
        Cart cart = new Cart(product, quantity, user);
        cart.setCreatedDate(new Date());
        cartRepository.save(cart);
    }

    private int stockOf(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStock();
    }

    private OrderStatus statusOfSession(String sessionId) {
        return orderRepository.findBySessionIdWithItems(sessionId).orElseThrow().getStatus();
    }
}
