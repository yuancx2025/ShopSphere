package ecommerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ecommerce.exceptions.BadRequestException;
import ecommerce.exceptions.CheckoutAttemptClosedException;
import ecommerce.model.User;
import ecommerce.payment.dto.CheckoutSessionResponseDto;
import ecommerce.payment.fake.FakePaymentGateway;
import ecommerce.repository.UserRepository;
import ecommerce.support.Contenders;
import ecommerce.support.MySqlIntegrationTest;
import ecommerce.support.TestData;

/** Repeated checkout requests: the Idempotency-Key binds one attempt to one order and one provider session. */
class CheckoutIdempotencyTest extends MySqlIntegrationTest {

    private static final int SKU = 1;
    private static final int STOCK = 5;
    private static final int USER = 7001;

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
        data.product(SKU, "Kettle", "24.50", STOCK);
        data.users(USER, 1);
        data.cartLine(USER, SKU, 2);
        user = userRepository.findById(USER).orElseThrow();
    }

    @Test
    void concurrentRequestsWithTheSameKeyReserveOnce() throws Exception {
        List<Callable<String>> clicks = new ArrayList<>();
        for (int n = 0; n < 10; n++) {
            clicks.add(() -> orderService.createSession(user, "double-click-1").getSessionId());
        }

        List<String> sessionIds = Contenders.race(clicks);

        assertThat(sessionIds).doesNotContainNull().hasSize(10);
        assertThat(sessionIds.stream().distinct()).hasSize(1);
        assertThat(data.count("SELECT COUNT(*) FROM orders WHERE user_id = ?", USER)).isEqualTo(1);
        assertThat(gateway.sessionsCreated()).isEqualTo(1);
        assertThat(data.stock(SKU)).isEqualTo(STOCK - 2);
    }

    @Test
    void replayReturnsTheSameSessionWithoutCallingTheProvider() throws Exception {
        CheckoutSessionResponseDto first = orderService.createSession(user, "replay-key-1");
        int callsAfterFirst = gateway.createCalls();

        CheckoutSessionResponseDto replay = orderService.createSession(user, "replay-key-1");

        assertThat(replay.getSessionId()).isEqualTo(first.getSessionId());
        assertThat(replay.getUrl()).isEqualTo(first.getUrl());
        assertThat(gateway.createCalls()).isEqualTo(callsAfterFirst);
        assertThat(data.stock(SKU)).isEqualTo(STOCK - 2);
    }

    @Test
    void newKeyCancelsThePreviousAttemptAndReleasesItsStock() throws Exception {
        CheckoutSessionResponseDto first = orderService.createSession(user, "attempt-a-1");

        CheckoutSessionResponseDto second = orderService.createSession(user, "attempt-b-1");

        assertThat(second.getSessionId()).isNotEqualTo(first.getSessionId());
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE session_id = ?", String.class,
                first.getSessionId())).isEqualTo("CANCELLED");
        assertThat(gateway.isExpired(first.getSessionId())).isTrue();
        assertThat(data.stock(SKU)).isEqualTo(STOCK - 2);
        assertThat(data.heldUnits(SKU)).isEqualTo(2);
    }

    @Test
    void replayOfAFinishedAttemptIsRejected() throws Exception {
        CheckoutSessionResponseDto first = orderService.createSession(user, "finished-1");
        gateway.pay(first.getSessionId());
        orderService.fulfillCheckout(first.getSessionId());
        data.cartLine(USER, SKU, 1);

        assertThatThrownBy(() -> orderService.createSession(user, "finished-1"))
                .isInstanceOf(CheckoutAttemptClosedException.class);
        assertThat(data.stock(SKU)).isEqualTo(STOCK - 2);
    }

    @Test
    void malformedKeyIsRejectedBeforeAnyReservation() {
        assertThatThrownBy(() -> orderService.createSession(user, "bad key!"))
                .isInstanceOf(BadRequestException.class);
        assertThat(data.stock(SKU)).isEqualTo(STOCK);
    }
}
