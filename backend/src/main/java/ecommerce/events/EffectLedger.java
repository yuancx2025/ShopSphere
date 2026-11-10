package ecommerce.events;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code consumed_events} table. {@link #applyOnce} inserts a consumer's marker for an event and runs its
 * effect in one MySQL transaction:
 * <ul>
 * <li>A duplicate delivery finds the marker (the insert affects no row) and skips the effect. A concurrent
 *     duplicate waits on the primary-key lock, then finds it.</li>
 * <li>If the effect throws, the marker rolls back and the broker redelivers.</li>
 * <li>If the process dies after the effect but before the commit, the marker is gone and the redelivery repeats
 *     the effect. Both effects are idempotent (a versioned upsert, a cache eviction), so that repeat changes
 *     nothing, and the marker still commits exactly once.</li>
 * </ul>
 */
@Repository
public class EffectLedger {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public EffectLedger(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** Returns true if this call applied the effect, false if the event was already applied for this group. */
    public boolean applyOnce(String consumerGroup, ProductChanged event, Runnable effect) {
        Boolean applied = tx.execute(status -> {
            int inserted = jdbc.update("INSERT IGNORE INTO consumed_events"
                            + " (consumer_group, event_id, product_id, event_created_at)"
                            + " VALUES (:group, :eventId, :productId, FROM_UNIXTIME(:createdEpoch))",
                    new MapSqlParameterSource("group", consumerGroup)
                            .addValue("eventId", event.eventId())
                            .addValue("productId", event.productId())
                            .addValue("createdEpoch", epochSeconds(event)));
            if (inserted == 0) {
                return false;
            }
            effect.run();
            return true;
        });
        return Boolean.TRUE.equals(applied);
    }

    public int count(String consumerGroup, long eventId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM consumed_events WHERE consumer_group = :group"
                + " AND event_id = :eventId", new MapSqlParameterSource("group", consumerGroup)
                .addValue("eventId", eventId), Integer.class);
        return n == null ? 0 : n;
    }

    private static java.math.BigDecimal epochSeconds(ProductChanged event) {
        return java.math.BigDecimal.valueOf(event.occurredAt().getEpochSecond())
                .add(java.math.BigDecimal.valueOf(event.occurredAt().getNano(), 9));
    }
}
