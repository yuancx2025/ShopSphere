package ecommerce.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** The publisher's view of {@code search_index_jobs}: unpublished rows and their publication marks (V9). */
@Repository
public class CatalogOutbox {

    private final NamedParameterJdbcTemplate jdbc;

    public CatalogOutbox(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The oldest unpublished rows as messages. One publisher per backend; see {@link OutboxPublisher}. */
    public List<ProductChanged> unpublished(int limit) {
        // UNIX_TIMESTAMP reads the DATETIME in the session time zone, so the instant is right whatever the JVM's
        // zone is (reading the DATETIME through JDBC would assume the JVM's zone).
        return jdbc.query("SELECT id, product_id, change_type, UNIX_TIMESTAMP(created_at) AS created_epoch"
                        + " FROM search_index_jobs WHERE published_at IS NULL ORDER BY id LIMIT :limit",
                new MapSqlParameterSource("limit", limit),
                (rs, n) -> ProductChanged.of(rs.getLong("id"), rs.getInt("product_id"),
                        ProductChange.valueOf(rs.getString("change_type")), epoch(rs.getBigDecimal("created_epoch"))));
    }

    /** Call only after the broker accepted every listed event. */
    public void markPublished(List<Long> eventIds) {
        if (!eventIds.isEmpty()) {
            jdbc.update("UPDATE search_index_jobs SET published_at = NOW(6) WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", eventIds));
        }
    }

    public long pending() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM search_index_jobs WHERE published_at IS NULL",
                new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }

    /** Age of the oldest unpublished row in seconds, 0 when none is pending. */
    public double oldestPendingAgeSeconds() {
        Double age = jdbc.queryForObject("SELECT COALESCE(TIMESTAMPDIFF(MICROSECOND, MIN(created_at), NOW(6)), 0)"
                + " / 1e6 FROM search_index_jobs WHERE published_at IS NULL", new MapSqlParameterSource(), Double.class);
        return age == null ? 0 : age;
    }

    static Instant epoch(BigDecimal seconds) {
        BigDecimal micros = seconds.movePointRight(6);
        return Instant.EPOCH.plusNanos(micros.longValueExact() * 1_000);
    }
}
