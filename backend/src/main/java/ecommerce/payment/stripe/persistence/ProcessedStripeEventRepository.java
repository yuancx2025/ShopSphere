package ecommerce.payment.stripe.persistence;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Webhook event inbox. The primary key on {@code event_id} is what makes claiming atomic, so
 * every state change here is a native statement rather than a read-then-write through JPA.
 */
@Repository
public interface ProcessedStripeEventRepository extends JpaRepository<ProcessedStripeEvent, String> {

    /**
     * Takes ownership of an event id. Returns 1 when this caller won the race and 0 when the
     * row already existed, which is the whole point: the database decides, not the application.
     */
    @Modifying
    @Query(
            value = """
                    INSERT INTO processed_stripe_events (event_id, status, claimed_at)
                    VALUES (:eventId, 'CLAIMED', :claimedAt)
                    ON CONFLICT (event_id) DO NOTHING
                    """,
            nativeQuery = true)
    int insertClaim(@Param("eventId") String eventId, @Param("claimedAt") Instant claimedAt);

    /**
     * Retakes a claim abandoned by a crashed handler. Returns 1 only when the row is still
     * CLAIMED and older than {@code staleBefore}; COMPLETED rows are never reprocessed.
     */
    @Modifying
    @Query(
            value = """
                    UPDATE processed_stripe_events
                    SET claimed_at = :now
                    WHERE event_id = :eventId
                      AND status = 'CLAIMED'
                      AND claimed_at < :staleBefore
                    """,
            nativeQuery = true)
    int reclaimStale(
            @Param("eventId") String eventId,
            @Param("now") Instant now,
            @Param("staleBefore") Instant staleBefore);

    @Modifying
    @Query(
            value = """
                    UPDATE processed_stripe_events
                    SET status = 'COMPLETED', processed_at = :processedAt
                    WHERE event_id = :eventId
                    """,
            nativeQuery = true)
    int markCompleted(@Param("eventId") String eventId, @Param("processedAt") Instant processedAt);

    /** Drops an unfinished claim so the provider's retry can re-enter. */
    @Modifying
    @Query(
            value = "DELETE FROM processed_stripe_events WHERE event_id = :eventId AND status = 'CLAIMED'",
            nativeQuery = true)
    int releaseClaim(@Param("eventId") String eventId);
}
