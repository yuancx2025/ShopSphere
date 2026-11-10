package ecommerce.search;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Drains {@code search_index_jobs} into Elasticsearch.
 *
 * <p>Each drain reads the oldest jobs, loads the <em>current</em> row for each distinct product, and bulk-upserts
 * them with {@code version_type=external} and {@code version = products.search_version}. Then it deletes the
 * jobs. That makes every step safe to repeat:
 * <ul>
 * <li>A replayed job, or one drained twice after a crash between the upsert and the delete, re-sends the same
 *     version; Elasticsearch answers with a version conflict, which counts as done.</li>
 * <li>A snapshot read before an admin edit carries a lower version than the edit's own job, so it can never
 *     overwrite the newer document, whichever bulk request arrives first. This is what makes a rebuild safe
 *     while admins keep editing.</li>
 * <li>If Elasticsearch is down, the jobs stay in MySQL with their attempt count bumped and are retried on the
 *     next drain. A backend restart loses nothing, because the queue is the table.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "search.elasticsearch.enabled", havingValue = "true")
public class ProductIndexer {

    private static final Logger log = LoggerFactory.getLogger(ProductIndexer.class);
    private static final String MAPPING = "search/products-index.json";

    private final ElasticsearchClient client;
    private final SearchIndexJobs jobs;
    private final MySqlSearchEngine rows;
    private final String index;
    private final int batchSize;
    private final Timer lag;
    private final Counter failures;
    private final Counter indexed;
    private volatile boolean indexReady;

    public ProductIndexer(ElasticsearchClient client, SearchIndexJobs jobs, MySqlSearchEngine rows,
            SearchProperties props, MeterRegistry meters) {
        this.client = client;
        this.jobs = jobs;
        this.rows = rows;
        this.index = props.elasticsearch().index();
        this.batchSize = props.indexer().batchSize();
        this.lag = Timer.builder("search.index.lag")
                .description("Time from a committed index job to Elasticsearch acknowledging its upsert")
                .publishPercentileHistogram()
                .register(meters);
        this.failures = Counter.builder("search.index.failures")
                .description("Drains that left their jobs queued because Elasticsearch failed")
                .register(meters);
        this.indexed = Counter.builder("search.index.documents")
                .description("Documents sent to Elasticsearch (including version conflicts)")
                .register(meters);
    }

    public int batchSize() {
        return batchSize;
    }

    /**
     * Processes up to one batch of jobs. Returns how many jobs it completed: 0 when the queue was empty or
     * Elasticsearch failed. Synchronized with {@link #rebuild()} so a bulk request never lands on a deleted index
     * (which Elasticsearch would recreate with a dynamic mapping).
     */
    public synchronized int drainOnce() {
        List<SearchIndexJobs.Job> batch = jobs.oldest(batchSize);
        if (batch.isEmpty()) {
            return 0;
        }
        long started = System.nanoTime();
        List<Long> jobIds = new ArrayList<>(batch.size());
        Set<Integer> productIds = new LinkedHashSet<>();
        for (SearchIndexJobs.Job job : batch) {
            jobIds.add(job.id());
            productIds.add(job.productId());
        }
        try {
            ensureIndex();
            upsert(rows.documents(productIds));
        } catch (IOException | ElasticsearchException | IllegalStateException ex) {
            failures.increment();
            jobs.recordFailedAttempt(jobIds);
            log.warn("Search indexing failed for {} jobs; they stay queued: {}", jobIds.size(), ex.toString());
            return 0;
        }
        jobs.delete(jobIds);
        long elapsedMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started);
        for (SearchIndexJobs.Job job : batch) {
            lag.record(job.ageMicros() + elapsedMicros, TimeUnit.MICROSECONDS);
        }
        return batch.size();
    }

    /** Drains until the queue is empty or a drain fails. Returns the jobs completed. */
    public int drainAll() {
        int total = 0;
        int done;
        do {
            done = drainOnce();
            total += done;
        } while (done == batchSize);
        return total;
    }

    /**
     * Deletes and recreates the index with the current mapping, then queues every product. Admin writes during the
     * rebuild queue their own jobs with higher versions, so the rebuilt index converges to MySQL.
     * Search returns partial results until the backfill drains.
     */
    public synchronized int rebuild() throws IOException {
        client.indices().delete(d -> d.index(index).ignoreUnavailable(true));
        indexReady = false;
        ensureIndex();
        return jobs.enqueueAll();
    }

    /** Makes recent upserts visible to search now instead of at the next 1 s refresh. Tests and probes use it. */
    public void refresh() throws IOException {
        client.indices().refresh(r -> r.index(index));
    }

    public long documentCount() throws IOException {
        return client.count(c -> c.index(index)).count();
    }

    private void ensureIndex() throws IOException {
        if (indexReady) {
            return;
        }
        if (!client.indices().exists(e -> e.index(index)).value()) {
            try (InputStream mapping = new ClassPathResource(MAPPING).getInputStream()) {
                client.indices().create(c -> c.index(index).withJson(mapping));
            } catch (ElasticsearchException ex) {
                // Another backend created it first.
                if (!"resource_already_exists_exception".equals(ex.error().type())) {
                    throw ex;
                }
            }
            log.info("Created Elasticsearch index {}", index);
        }
        indexReady = true;
    }

    /** Package-private so the test can send a stale snapshot the way a slow concurrent indexer would. */
    void upsert(List<Map<String, Object>> products) throws IOException {
        if (products.isEmpty()) {
            return; // Every product in the batch was deleted; nothing to index.
        }
        BulkRequest.Builder bulk = new BulkRequest.Builder().index(index);
        for (Map<String, Object> row : products) {
            Map<String, Object> doc = new LinkedHashMap<>(row);
            String id = String.valueOf(row.get("id"));
            long version = ((Number) row.get("search_version")).longValue();
            bulk.operations(op -> op.index(i -> i.id(id).document(doc).version(version)
                    .versionType(VersionType.External)));
        }
        BulkResponse response = client.bulk(bulk.build());
        indexed.increment(products.size());
        if (!response.errors()) {
            return;
        }
        for (BulkResponseItem item : response.items()) {
            if (item.error() != null && !"version_conflict_engine_exception".equals(item.error().type())) {
                throw new IllegalStateException("Bulk upsert of product " + item.id() + " failed: "
                        + item.error().type() + " " + item.error().reason());
            }
        }
    }
}
