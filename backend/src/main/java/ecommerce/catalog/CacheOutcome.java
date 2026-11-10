package ecommerce.catalog;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Tells a cache hit from a miss for the {@code X-Cache} response header. A {@code @Cacheable} method body runs only
 * on a miss (including a Redis error or an open bypass window), so the body calls {@link #markLoaded()} and the
 * controller reads it back. Benchmarks use the header to report hit and miss latency separately.
 */
public final class CacheOutcome {

    public static final String HEADER = "X-Cache";

    private static final String LOADED = CacheOutcome.class.getName() + ".loaded";

    private CacheOutcome() {
    }

    /** Called from a cached method's body: this request read the database. */
    public static void markLoaded() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null) {
            request.setAttribute(LOADED, Boolean.TRUE, RequestAttributes.SCOPE_REQUEST);
        }
    }

    /** {@code MISS} if the cached method's body ran during this request, else {@code HIT}. */
    public static String forCurrentRequest() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        boolean loaded = request != null
                && Boolean.TRUE.equals(request.getAttribute(LOADED, RequestAttributes.SCOPE_REQUEST));
        return loaded ? "MISS" : "HIT";
    }
}
