package ecommerce.search;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code search.*} settings.
 *
 * @param engine default engine for {@code GET /product/search} when the request has no {@code engine} parameter:
 *        {@code mysql} (FULLTEXT baseline) or {@code elasticsearch}
 * @param elasticsearch Elasticsearch settings; the client address is Spring Boot's {@code spring.elasticsearch.uris}
 * @param indexer the background worker that drains {@code search_index_jobs} into Elasticsearch
 */
@ConfigurationProperties(prefix = "search")
public record SearchProperties(String engine, Elasticsearch elasticsearch, Indexer indexer) {

    public SearchProperties {
        engine = engine == null ? "mysql" : engine;
        elasticsearch = elasticsearch == null ? new Elasticsearch(false, null) : elasticsearch;
        indexer = indexer == null ? new Indexer(null, null, 0) : indexer;
    }

    /** @param enabled turns on the Elasticsearch engine and the indexer */
    public record Elasticsearch(boolean enabled, String index) {
        public Elasticsearch {
            index = index == null || index.isBlank() ? "products" : index;
        }
    }

    /**
     * @param schedule run the worker on a timer (tests turn this off and call {@code drainOnce()} directly)
     * @param pollInterval delay between drains when the queue was empty
     * @param batchSize jobs per Elasticsearch bulk request
     */
    public record Indexer(Boolean schedule, Duration pollInterval, int batchSize) {
        public Indexer {
            schedule = schedule == null ? Boolean.TRUE : schedule;
            pollInterval = pollInterval == null ? Duration.ofMillis(200) : pollInterval;
            batchSize = batchSize <= 0 ? 500 : batchSize;
        }
    }
}
