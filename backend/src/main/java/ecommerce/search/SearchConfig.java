package ecommerce.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableConfigurationProperties(SearchProperties.class)
public class SearchConfig {

    /**
     * Runs the indexer on a timer when Elasticsearch is on and scheduling isn't turned off (tests drain by hand).
     * With Sprint 5 events on, the outbox goes to RocketMQ instead and the search consumer indexes.
     */
    @Configuration
    @EnableScheduling
    @ConditionalOnExpression("${search.elasticsearch.enabled:false} and ${search.indexer.schedule:true}"
            + " and !${events.enabled:false}")
    static class IndexerSchedule {

        private final ProductIndexer indexer;

        IndexerSchedule(ProductIndexer indexer) {
            this.indexer = indexer;
        }

        /** Drains the whole queue, then waits one poll interval. A backfill therefore runs at bulk speed. */
        // Spring 6.1.3 parses only ISO-8601 or milliseconds here, so the property uses ISO-8601 (PT0.2S).
        @Scheduled(fixedDelayString = "${search.indexer.poll-interval:PT0.2S}", initialDelayString = "PT1S")
        void drain() {
            indexer.drainAll();
        }
    }
}
