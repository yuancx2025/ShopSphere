package ecommerce.search;

import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import ecommerce.events.ProductChange;

/**
 * The {@code search_index_jobs} table: the outbox between admin catalog writes and their consumers. With
 * {@code events.enabled=false} {@link ProductIndexer} drains it into Elasticsearch directly; with events on,
 * {@code OutboxPublisher} sends it to RocketMQ (see V9). Jobs are written whether or not either is enabled, so
 * turning one on later needs only a backfill.
 */
@Repository
public class SearchIndexJobs {

    /** A queued job, with its age in microseconds measured by MySQL when it was claimed. */
    public record Job(long id, int productId, long ageMicros) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public SearchIndexJobs(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Queues a product change. Call inside the transaction that changes the product. */
    public void enqueue(int productId, ProductChange change) {
        jdbc.update("INSERT INTO search_index_jobs (product_id, change_type) VALUES (:productId, :change)",
                new MapSqlParameterSource("productId", productId).addValue("change", change.name()));
    }

    /** Queues every product (the backfill). Returns the number of jobs written. */
    public int enqueueAll() {
        return jdbc.update("INSERT INTO search_index_jobs (product_id) SELECT id FROM products",
                new MapSqlParameterSource());
    }

    /**
     * The oldest jobs. One worker per backend; if two backends drain the same jobs, the second upsert is a version
     * conflict and a no-op, so no locking is needed.
     */
    public List<Job> oldest(int limit) {
        return jdbc.query("SELECT id, product_id, TIMESTAMPDIFF(MICROSECOND, created_at, NOW(6)) AS age_us"
                        + " FROM search_index_jobs ORDER BY id LIMIT :limit",
                new MapSqlParameterSource("limit", limit),
                (rs, n) -> new Job(rs.getLong("id"), rs.getInt("product_id"), rs.getLong("age_us")));
    }

    public void delete(List<Long> jobIds) {
        jdbc.update("DELETE FROM search_index_jobs WHERE id IN (:ids)", new MapSqlParameterSource("ids", jobIds));
    }

    public void recordFailedAttempt(List<Long> jobIds) {
        jdbc.update("UPDATE search_index_jobs SET attempts = attempts + 1 WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", jobIds));
    }

    public int pending() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM search_index_jobs", new MapSqlParameterSource(),
                Integer.class);
        return count == null ? 0 : count;
    }
}
