package ecommerce.events;

import java.util.Objects;

import org.apache.rocketmq.client.exception.MQClientException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import static ecommerce.catalog.CatalogCacheConfig.CATALOG_PAGE;
import static ecommerce.catalog.CatalogCacheConfig.PRODUCT_DETAIL;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Consumer group {@value #GROUP}: evicts the product's cached detail and every cached catalog page.
 *
 * <p>The admin write already evicts after its commit ({@code @CacheEvict}). This is the delayed second eviction
 * that closes the race where a reader loads the old row before the commit and writes it to Redis after that first
 * eviction. Unlike the annotation path, a Redis error here is thrown, so the event is retried instead of dropped.
 */
@Component
@ConditionalOnExpression("${events.enabled:false} and '${spring.cache.type:none}' == 'redis'")
public class CacheInvalidationConsumer extends ProductChangedConsumer {

    public static final String GROUP = "cache-invalidator";

    private final CacheManager caches;

    public CacheInvalidationConsumer(CacheManager caches, EffectLedger ledger, ObjectMapper json,
            EventsProperties props, MeterRegistry meters) throws MQClientException {
        super(GROUP, ledger, json, props, meters);
        this.caches = caches;
    }

    @Override
    protected void apply(ProductChanged event) {
        Objects.requireNonNull(caches.getCache(PRODUCT_DETAIL)).evict(event.productId());
        Objects.requireNonNull(caches.getCache(CATALOG_PAGE)).clear();
    }
}
