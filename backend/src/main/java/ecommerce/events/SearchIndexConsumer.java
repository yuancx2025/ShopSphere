package ecommerce.events;

import java.util.List;

import org.apache.rocketmq.client.exception.MQClientException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import ecommerce.search.ProductIndexer;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Consumer group {@value #GROUP}: upserts the product's current row into Elasticsearch. The upsert carries
 * {@code search_version} as an external version, so a repeat is a version conflict and changes nothing.
 */
@Component
@ConditionalOnExpression("${events.enabled:false} and ${events.consumer.enabled:true}"
        + " and ${search.elasticsearch.enabled:false}")
public class SearchIndexConsumer extends ProductChangedConsumer {

    public static final String GROUP = "search-indexer";

    private final ProductIndexer indexer;

    public SearchIndexConsumer(ProductIndexer indexer, EffectLedger ledger, ObjectMapper json,
            EventsProperties props, MeterRegistry meters) throws MQClientException {
        super(GROUP, ledger, json, props, meters);
        this.indexer = indexer;
    }

    @Override
    protected void apply(ProductChanged event) throws Exception {
        indexer.indexNow(List.of(event.productId()));
    }
}
