package ecommerce.payment.stripe;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ecommerce.payment.stripe.persistence.ProcessedStripeEventRepository;

/**
 * Webhook inbox for Stripe events. Stripe delivers at least once, so the same {@code evt_...}
 * id can arrive twice, concurrently, or after a handler crashed part-way through.
 *
 * <p>Claiming is a single atomic insert rather than an "have I seen this?" check followed by a
 * later write: two simultaneous deliveries would both pass a check-then-act and both run the
 * business logic.
 */
@Service
public class StripeEventService {

    private static final Logger log = LoggerFactory.getLogger(StripeEventService.class);

    /** A claim older than this is treated as abandoned by a crashed handler and can be retaken. */
    private static final Duration STALE_CLAIM = Duration.ofMinutes(15);

    private final ProcessedStripeEventRepository processedStripeEventRepository;

    public StripeEventService(ProcessedStripeEventRepository processedStripeEventRepository) {
        this.processedStripeEventRepository = processedStripeEventRepository;
    }

    /**
     * Attempts to take ownership of an event id.
     *
     * @return true when this caller should process the event; false when another delivery already
     *         owns it or finished it, in which case the caller should acknowledge and do nothing.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return false;
        }
        Instant now = Instant.now();
        if (processedStripeEventRepository.insertClaim(eventId, now) == 1) {
            return true;
        }
        // Row exists: either COMPLETED, or CLAIMED by a live handler, or CLAIMED by a dead one.
        if (processedStripeEventRepository.reclaimStale(eventId, now, now.minus(STALE_CLAIM)) == 1) {
            log.warn("Retook stale webhook claim for event {}", eventId);
            return true;
        }
        return false;
    }

    /** Marks the event finished so later deliveries of the same id are ignored. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        processedStripeEventRepository.markCompleted(eventId, Instant.now());
    }

    /**
     * Releases an unfinished claim after a handler failure, so Stripe's retry is not silently
     * swallowed by the inbox.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        processedStripeEventRepository.releaseClaim(eventId);
    }
}
