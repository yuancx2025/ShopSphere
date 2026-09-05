package ecommerce.payment;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link PaymentGateway} for tests.
 *
 * <p>Payment providers are the least deterministic part of checkout: they are remote, they settle
 * asynchronously, and they cannot be told to fail on demand. Driving {@code OrderService} through
 * this fake makes every state transition reproducible without a network call, which is the whole
 * point of keeping the gateway behind a port.
 */
public class FakePaymentGateway implements PaymentGateway {

    private final Map<String, Recorded> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();

    private volatile boolean failNextCreation;
    private volatile String currency = "usd";

    // ---------------------------------------------------------------------
    // PaymentGateway
    // ---------------------------------------------------------------------

    @Override
    public PaymentSession createSession(CheckoutCommand command) {
        if (failNextCreation) {
            failNextCreation = false;
            throw new PaymentException("Simulated provider outage", new IllegalStateException("boom"));
        }
        long total = 0;
        for (CheckoutCommand.LineItem item : command.getLineItems()) {
            total += item.getUnitAmountCents() * item.getQuantity();
        }
        String sessionId = "cs_test_" + sequence.incrementAndGet();
        Recorded recorded = new Recorded(sessionId, command.getOrderId(), total, currency);
        sessions.put(sessionId, recorded);
        return recorded.toSession();
    }

    @Override
    public PaymentSession retrieveSession(String sessionId) {
        Recorded recorded = sessions.get(sessionId);
        if (recorded == null) {
            throw new PaymentException("No such session: " + sessionId, new IllegalArgumentException(sessionId));
        }
        return recorded.toSession();
    }

    @Override
    public void expireSession(String sessionId) {
        Recorded recorded = sessions.get(sessionId);
        // Real gateways ignore expiry of an already-settled session.
        if (recorded != null && recorded.status == PaymentStatus.CREATED) {
            recorded.status = PaymentStatus.EXPIRED;
        }
    }

    @Override
    public long minimumChargeCents() {
        return 50;
    }

    @Override
    public String expectedCurrency() {
        return currency;
    }

    // ---------------------------------------------------------------------
    // Test controls
    // ---------------------------------------------------------------------

    /** The customer paid successfully. */
    public void settle(String sessionId) {
        require(sessionId).status = PaymentStatus.SUCCEEDED;
    }

    /** The customer completed checkout with a delayed method that has not settled. */
    public void processing(String sessionId) {
        require(sessionId).status = PaymentStatus.PROCESSING;
    }

    public void expire(String sessionId) {
        require(sessionId).status = PaymentStatus.EXPIRED;
    }

    public void fail(String sessionId) {
        require(sessionId).status = PaymentStatus.FAILED;
    }

    /** Simulates a provider reporting a different total than the one we priced. */
    public void reportAmount(String sessionId, Long amountMinor) {
        require(sessionId).amountMinor = amountMinor;
    }

    /** Simulates settlement in a currency we did not price in. */
    public void reportCurrency(String sessionId, String reported) {
        require(sessionId).currency = reported;
    }

    /** The next {@link #createSession} throws, as if the provider timed out. */
    public void failNextCreation() {
        this.failNextCreation = true;
    }

    public void reset() {
        sessions.clear();
        sequence.set(0);
        failNextCreation = false;
        currency = "usd";
    }

    private Recorded require(String sessionId) {
        Recorded recorded = sessions.get(sessionId);
        if (recorded == null) {
            throw new IllegalArgumentException("Unknown session " + sessionId);
        }
        return recorded;
    }

    /** Mutable server-side view of one hosted checkout session. */
    private static final class Recorded {
        private final String sessionId;
        private final Integer orderId;
        private volatile Long amountMinor;
        private volatile String currency;
        private volatile PaymentStatus status = PaymentStatus.CREATED;

        private Recorded(String sessionId, Integer orderId, long amountMinor, String currency) {
            this.sessionId = sessionId;
            this.orderId = orderId;
            this.amountMinor = amountMinor;
            this.currency = currency;
        }

        private PaymentSession toSession() {
            return new PaymentSession(
                    sessionId,
                    "https://fake.checkout.test/" + sessionId,
                    status,
                    status.name().toLowerCase(),
                    amountMinor,
                    currency,
                    orderId);
        }
    }
}
