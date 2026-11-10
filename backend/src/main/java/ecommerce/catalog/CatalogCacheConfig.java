package ecommerce.catalog;

import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.metrics.cache.CacheMeterBinderProvider;
import org.springframework.boot.actuate.metrics.cache.RedisCacheMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import com.fasterxml.jackson.databind.ObjectMapper;

import ecommerce.dto.product.ProductDto;
import ecommerce.dto.product.ProductPageDto;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * Cache-aside for catalog reads (Sprint 2). Off by default: {@code spring.cache.type=none} makes every
 * {@code @Cacheable} a plain method call, and {@code spring.cache.type=redis} turns on the Redis cache below.
 * That property is the A/B feature flag for the benchmark.
 *
 * <p>Two choices matter for correctness:
 * <ul>
 * <li>The caching interceptor runs <em>outside</em> the transaction ({@code order = HIGHEST_PRECEDENCE}), so
 * {@code @CacheEvict} fires after the admin write commits, and a cache hit never opens a transaction or borrows a
 * database connection.</li>
 * <li>Redis errors never fail a request. {@link #errorHandler()} counts them and the read falls through to MySQL.
 * Short timeouts and {@code REJECT_COMMANDS} while disconnected keep that fallback fast, and after an error
 * {@link RedisBypassCache} skips Redis for {@code catalog.cache.bypass-after-error}, so a hung Redis (or a JVM
 * pause that makes every in-flight command time out at once) costs one timeout rather than one per request.</li>
 * </ul>
 */
@Configuration
@EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)
public class CatalogCacheConfig implements CachingConfigurer {

    public static final String PRODUCT_DETAIL = "productDetail";
    public static final String CATALOG_PAGE = "catalogPage";
    /** Part of every key. Bump the version when a cached DTO changes shape so old entries are never read. */
    static final String KEY_PREFIX = "shopsphere:catalog:v1:";

    private final ObjectProvider<MeterRegistry> meterRegistry;
    private final RedisBypassCache.Window bypassWindow;

    public CatalogCacheConfig(ObjectProvider<MeterRegistry> meterRegistry,
            @Value("${catalog.cache.bypass-after-error}") Duration bypassAfterError) {
        this.meterRegistry = meterRegistry;
        this.bypassWindow = new RedisBypassCache.Window(bypassAfterError);
    }

    @Bean
    @ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis")
    RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper,
            @Value("${catalog.cache.ttl}") Duration ttl) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                .prefixCacheNameWith(KEY_PREFIX);
        // SCAN instead of KEYS when an admin write clears the page cache. The statistics collector feeds
        // cache_gets_total{result="hit|miss"}.
        RedisCacheWriter writer = RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory,
                BatchStrategies.scan(500)).withStatisticsCollector(CacheStatisticsCollector.create());
        // Plain JSON with the HTTP ObjectMapper, so a cached body serializes exactly like an uncached one.
        Map<String, RedisCacheConfiguration> caches = Map.of(
                PRODUCT_DETAIL, defaults.serializeValuesWith(SerializationPair.fromSerializer(
                        new Jackson2JsonRedisSerializer<>(objectMapper, ProductDto.class))),
                CATALOG_PAGE, defaults.serializeValuesWith(SerializationPair.fromSerializer(
                        new Jackson2JsonRedisSerializer<>(objectMapper, ProductPageDto.class))));
        return new RedisCacheManager(writer, defaults, false, caches) {
            @Override
            protected Cache decorateCache(Cache cache) {
                return new RedisBypassCache((RedisCache) cache, bypassWindow, meterRegistry);
            }
        };
    }

    /** Binds Boot's Redis hit/miss metrics to the Redis cache inside each {@link RedisBypassCache}. */
    @Bean
    CacheMeterBinderProvider<RedisBypassCache> redisBypassCacheMetrics() {
        return new BypassCacheMetrics();
    }

    static final class BypassCacheMetrics implements CacheMeterBinderProvider<RedisBypassCache> {
        @Override
        public MeterBinder getMeterBinder(RedisBypassCache cache, Iterable<Tag> tags) {
            return new RedisCacheMetrics(cache.redis(), tags);
        }
    }

    /** Fail fast while Redis is down instead of queueing commands until they time out. */
    @Bean
    LettuceClientConfigurationBuilderCustomizer failFastWhenRedisIsDown(
            @Value("${spring.data.redis.connect-timeout}") Duration connectTimeout) {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build())
                .timeoutOptions(TimeoutOptions.enabled())
                .build());
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CountingErrorHandler(meterRegistry, bypassWindow);
    }

    /**
     * Treats a Redis failure as a miss (get) or a no-op (put, evict, clear), counts it as
     * {@code catalog_cache_errors_total{cache, operation}} and opens the bypass window. A failed evict leaves the
     * old entry until its TTL ends.
     */
    static final class CountingErrorHandler implements CacheErrorHandler {

        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CountingErrorHandler.class);

        private final ObjectProvider<MeterRegistry> meterRegistry;
        private final RedisBypassCache.Window bypassWindow;

        CountingErrorHandler(ObjectProvider<MeterRegistry> meterRegistry, RedisBypassCache.Window bypassWindow) {
            this.meterRegistry = meterRegistry;
            this.bypassWindow = bypassWindow;
        }

        @Override
        public void handleCacheGetError(RuntimeException ex, Cache cache, Object key) {
            record(ex, cache, "get");
        }

        @Override
        public void handleCachePutError(RuntimeException ex, Cache cache, Object key, Object value) {
            record(ex, cache, "put");
        }

        @Override
        public void handleCacheEvictError(RuntimeException ex, Cache cache, Object key) {
            record(ex, cache, "evict");
            log.warn("Could not evict {} from {}; it expires with its TTL: {}", key, cache.getName(), ex.toString());
        }

        @Override
        public void handleCacheClearError(RuntimeException ex, Cache cache) {
            record(ex, cache, "clear");
            log.warn("Could not clear {}; entries expire with their TTL: {}", cache.getName(), ex.toString());
        }

        private void record(RuntimeException ex, Cache cache, String operation) {
            bypassWindow.open();
            // Per request during an outage, so keep it out of the WARN log.
            log.debug("Redis {} on {} failed, falling back to MySQL: {}", operation, cache.getName(), ex.toString());
            MeterRegistry registry = meterRegistry.getIfAvailable();
            if (registry != null) {
                registry.counter("catalog.cache.errors", "cache", cache.getName(), "operation", operation)
                        .increment();
            }
        }
    }
}
