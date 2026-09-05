package ecommerce.payment.stripe.persistence;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row per Stripe webhook event id (Stripe's {@code evt_...} string), used as an inbox.
 *
 * <p>Rows are created by an atomic {@code INSERT ... ON CONFLICT DO NOTHING} in
 * {@link ProcessedStripeEventRepository}, not through JPA {@code save}, because an entity with an
 * assigned id would merge into the existing row instead of failing the uniqueness check.
 */
@Entity
@Table(name = "processed_stripe_events")
public class ProcessedStripeEvent {

    @Id
    @Column(name = "event_id")
    private String eventId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 20)
    private WebhookEventStatus status = WebhookEventStatus.CLAIMED;

    @Column(name = "claimed_at", nullable = false)
    private Instant claimedAt;

    /** Null while the event is still CLAIMED. */
    @Column(name = "processed_at")
    private Instant processedAt;

    public ProcessedStripeEvent() {
    }

    public ProcessedStripeEvent(String eventId, WebhookEventStatus status, Instant claimedAt) {
        this.eventId = eventId;
        this.status = status;
        this.claimedAt = claimedAt;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public WebhookEventStatus getStatus() {
        return status;
    }

    public void setStatus(WebhookEventStatus status) {
        this.status = status;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(Instant claimedAt) {
        this.claimedAt = claimedAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }
}
