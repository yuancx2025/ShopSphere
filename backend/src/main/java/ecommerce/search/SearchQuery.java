package ecommerce.search;

import java.math.BigDecimal;

import ecommerce.common.Money;
import ecommerce.exceptions.BadRequestException;

/**
 * A validated search request. Both engines receive the same object, so they apply the same filters, sort and
 * window.
 *
 * @param text the keywords, trimmed, 1–{@value #MAX_TEXT_LENGTH} characters
 * @param minCents inclusive lower price bound, or null
 * @param maxCents inclusive upper price bound, or null
 * @param offset rows to skip ({@code page * size})
 * @param size rows the client asked for (engines are asked for one more, to tell whether another page exists)
 */
public record SearchQuery(String text, Integer categoryId, Long minCents, Long maxCents, SearchSort sort,
        int page, int offset, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    /** Deepest result a client can page to. Search is for finding, not for walking the catalog. */
    public static final int MAX_WINDOW = 1000;
    public static final int MAX_TEXT_LENGTH = 200;

    public static SearchQuery of(String q, Integer categoryId, BigDecimal minPrice, BigDecimal maxPrice,
            String sort, Integer page, Integer size) {
        String text = q == null ? "" : q.trim();
        if (text.isEmpty()) {
            throw new BadRequestException("q is required");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new BadRequestException("q must be at most " + MAX_TEXT_LENGTH + " characters");
        }
        int pageSize = size == null ? DEFAULT_SIZE : size;
        if (pageSize < 1 || pageSize > MAX_SIZE) {
            throw new BadRequestException("size must be between 1 and " + MAX_SIZE);
        }
        int pageNumber = page == null ? 0 : page;
        if (pageNumber < 0) {
            throw new BadRequestException("page must not be negative");
        }
        long offset = (long) pageNumber * pageSize;
        if (offset + pageSize > MAX_WINDOW) {
            throw new BadRequestException("page * size + size must be at most " + MAX_WINDOW);
        }
        Long min = cents("minPrice", minPrice);
        Long max = cents("maxPrice", maxPrice);
        if (min != null && max != null && min > max) {
            throw new BadRequestException("minPrice must not exceed maxPrice");
        }
        return new SearchQuery(text, categoryId, min, max, SearchSort.fromParam(sort), pageNumber, (int) offset,
                pageSize);
    }

    private static Long cents(String name, BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        if (amount.signum() < 0) {
            throw new BadRequestException(name + " must not be negative");
        }
        try {
            return Money.toCents(amount);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(name + ": " + ex.getMessage());
        }
    }
}
