package ecommerce.payment.stripe;

import java.time.Instant;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import ecommerce.payment.stripe.persistence.ProcessedStripeEvent;
import ecommerce.payment.stripe.persistence.ProcessedStripeEventRepository;

/**
 * Idempotency for Stripe webhooks. Stripe may deliver the same event more than once;
 * recording {@code event.id} prevents double-fulfillment or double stock release.
 */
@Service
public class StripeEventService {

    private final ProcessedStripeEventRepository processedStripeEventRepository;

    public StripeEventService(ProcessedStripeEventRepository processedStripeEventRepository) {
        this.processedStripeEventRepository = processedStripeEventRepository;
    }

    public boolean alreadyProcessed(String eventId) {
        return eventId != null && processedStripeEventRepository.existsById(eventId);
    }

    public void recordProcessedEvent(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        if (processedStripeEventRepository.existsById(eventId)) {
            return;
        }
        try {
            processedStripeEventRepository.save(new ProcessedStripeEvent(eventId, Instant.now()));
        } catch (DataIntegrityViolationException ignored) {
            // Concurrent webhook deliveries: unique constraint on event_id wins once.
        }
    }
}
