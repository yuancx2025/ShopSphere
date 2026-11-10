package ecommerce.payment.fake;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import ecommerce.payment.CheckoutCommand;
import ecommerce.payment.PaymentException;
import ecommerce.payment.PaymentGateway;
import ecommerce.payment.PaymentSession;

/**
 * Deterministic in-memory payment provider for integration tests and the benchmark stack
 * ({@code payment.provider=fake}). It behaves like Stripe where checkout correctness depends on it:
 * <ul>
 *   <li>createSession is idempotent on the idempotency key and returns the first result for a retry;</li>
 *   <li>the collected amount is computed from the line items, like Stripe does;</li>
 *   <li>tests can script a rejected create-session call.</li>
 * </ul>
 * Never active by default, and it refuses to start under a {@code prod} profile.
 */
@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "fake")
public class FakePaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(FakePaymentGateway.class);

    /** What the next createSession call does. */
    public enum CreateBehavior {
        /** Create (or replay) the session and return it. */
        OK,
        /** Provider rejects the request; nothing is created. */
        REJECT
    }

    private final Map<String, FakeSession> byId = new ConcurrentHashMap<>();
    private final Map<String, String> idByIdempotencyKey = new ConcurrentHashMap<>();
    private final Deque<CreateBehavior> scripted = new ArrayDeque<>();
    private final AtomicInteger createCalls = new AtomicInteger();
    private final AtomicInteger sessionsCreated = new AtomicInteger();

    public FakePaymentGateway(Environment environment) {
        if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException("payment.provider=fake must not run with the prod profile");
        }
        log.warn("Using the FAKE payment gateway: no real payments are created");
    }

    // ------------------------------------------------------------------ PaymentGateway

    @Override
    public PaymentSession createSession(CheckoutCommand command) {
        createCalls.incrementAndGet();
        CreateBehavior behavior = nextBehavior();
        if (behavior == CreateBehavior.REJECT) {
            throw new PaymentException("fake provider rejected the request");
        }
        return createOrReplay(command).view();
    }

    @Override
    public PaymentSession retrieveSession(String sessionId) {
        FakeSession session = byId.get(sessionId);
        if (session == null) {
            throw new PaymentException("no such fake session " + sessionId);
        }
        return session.view();
    }

    @Override
    public void expireSession(String sessionId) {
        FakeSession session = byId.get(sessionId);
        if (session != null) {
            synchronized (session) {
                if (!"paid".equals(session.paymentStatus)) {
                    session.expired = true;
                }
            }
        }
    }

    @Override
    public long minimumChargeCents() {
        return 50;
    }

    // ------------------------------------------------------------------ test controls

    /** Queues behaviors for the next createSession calls; once used up, calls behave as {@link CreateBehavior#OK}. */
    public synchronized void script(CreateBehavior... behaviors) {
        scripted.addAll(Arrays.asList(behaviors));
    }

    /** The customer pays the session in full. Returns false if the session already expired. */
    public boolean pay(String sessionId) {
        FakeSession session = require(sessionId);
        synchronized (session) {
            if (session.expired) {
                return false;
            }
            session.paymentStatus = "paid";
            return true;
        }
    }

    /** Pays even an expired session: the race where a payment confirms after checkout was released. */
    public void payLate(String sessionId) {
        FakeSession session = require(sessionId);
        synchronized (session) {
            session.paymentStatus = "paid";
        }
    }

    /** Overrides what the provider reports it collected, for mismatch tests. */
    public void tamper(String sessionId, Long amountTotalCents, String currency, Integer metadataOrderId) {
        FakeSession session = require(sessionId);
        synchronized (session) {
            if (amountTotalCents != null) {
                session.amountTotalCents = amountTotalCents;
            }
            if (currency != null) {
                session.currency = currency;
            }
            if (metadataOrderId != null) {
                session.orderId = metadataOrderId;
            }
        }
    }

    public String sessionIdForIdempotencyKey(String idempotencyKey) {
        return idByIdempotencyKey.get(idempotencyKey);
    }

    public boolean isExpired(String sessionId) {
        return require(sessionId).expired;
    }

    public int createCalls() {
        return createCalls.get();
    }

    public int sessionsCreated() {
        return sessionsCreated.get();
    }

    public synchronized void reset() {
        byId.clear();
        idByIdempotencyKey.clear();
        scripted.clear();
        createCalls.set(0);
        sessionsCreated.set(0);
    }

    // ------------------------------------------------------------------ internals

    private synchronized CreateBehavior nextBehavior() {
        CreateBehavior next = scripted.pollFirst();
        return next == null ? CreateBehavior.OK : next;
    }

    private FakeSession createOrReplay(CheckoutCommand command) {
        String id = idByIdempotencyKey.computeIfAbsent(command.getIdempotencyKey(), key -> {
            String sessionId = "cs_fake_" + command.getOrderId() + "_" + sessionsCreated.incrementAndGet();
            long amount = command.getLineItems().stream()
                    .mapToLong(item -> Math.multiplyExact(item.getUnitAmountCents(), item.getQuantity()))
                    .sum();
            byId.put(sessionId, new FakeSession(sessionId, command.getOrderId(), amount, command.getCurrency()));
            return sessionId;
        });
        return byId.get(id);
    }

    private FakeSession require(String sessionId) {
        FakeSession session = byId.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("no such fake session " + sessionId);
        }
        return session;
    }

    private static final class FakeSession {
        final String id;
        Integer orderId;
        long amountTotalCents;
        String currency;
        String paymentStatus = "unpaid";
        boolean expired;

        FakeSession(String id, Integer orderId, long amountTotalCents, String currency) {
            this.id = id;
            this.orderId = orderId;
            this.amountTotalCents = amountTotalCents;
            this.currency = currency;
        }

        synchronized PaymentSession view() {
            return new PaymentSession(id, "https://pay.fake.test/" + id, paymentStatus, orderId, amountTotalCents,
                    currency);
        }
    }
}
