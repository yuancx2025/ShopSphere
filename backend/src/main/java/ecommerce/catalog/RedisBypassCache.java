package ecommerce.catalog;

import java.time.Duration;
import java.util.concurrent.Callable;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCache;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * A Redis cache that steps aside for a short window after a Redis error, so a slow or unreachable Redis costs one
 * timeout and then plain MySQL reads, instead of a timeout on every request.
 *
 * <p>While the window is open, reads are misses (the method runs against MySQL) and writes are skipped; both count
 * as {@code catalog_cache_bypassed_total}. Evictions always go to Redis, so an admin write never skips one.
 * The first request after the window tries Redis again. That is the whole "circuit breaker": no half-open state,
 * no error-rate threshold.
 */
final class RedisBypassCache implements Cache {

    /** Shared by every cache: one Redis, one window. */
    static final class Window {
        private final long lengthNanos;
        private volatile long openUntilNanos;

        Window(Duration length) {
            this.lengthNanos = length.toNanos();
            this.openUntilNanos = System.nanoTime();
        }

        void open() {
            openUntilNanos = System.nanoTime() + lengthNanos;
        }

        boolean isOpen() {
            return System.nanoTime() - openUntilNanos < 0;
        }
    }

    private final RedisCache redis;
    private final Window window;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    RedisBypassCache(RedisCache redis, Window window, ObjectProvider<MeterRegistry> meterRegistry) {
        this.redis = redis;
        this.window = window;
        this.meterRegistry = meterRegistry;
    }

    RedisCache redis() {
        return redis;
    }

    @Override
    public String getName() {
        return redis.getName();
    }

    @Override
    public Object getNativeCache() {
        return redis.getNativeCache();
    }

    @Override
    public ValueWrapper get(Object key) {
        return bypassed() ? null : redis.get(key);
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        return bypassed() ? null : redis.get(key, type);
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        if (bypassed()) {
            try {
                return valueLoader.call();
            } catch (Exception ex) {
                throw new ValueRetrievalException(key, valueLoader, ex);
            }
        }
        return redis.get(key, valueLoader);
    }

    @Override
    public void put(Object key, Object value) {
        if (!bypassed()) {
            redis.put(key, value);
        }
    }

    @Override
    public void evict(Object key) {
        redis.evict(key);
    }

    @Override
    public void clear() {
        redis.clear();
    }

    private boolean bypassed() {
        if (!window.isOpen()) {
            return false;
        }
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            registry.counter("catalog.cache.bypassed", "cache", getName()).increment();
        }
        return true;
    }
}
