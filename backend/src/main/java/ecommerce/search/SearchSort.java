package ecommerce.search;

import ecommerce.exceptions.BadRequestException;

/** Search result orderings. Every one ends in the product id, so both engines return the same total order. */
public enum SearchSort {
    /** Engine score, highest first, then id. */
    RELEVANCE("relevance"),
    /** {@code price_cents} ascending, then id. */
    PRICE_ASC("price_asc"),
    /** {@code price_cents} descending, then id. */
    PRICE_DESC("price_desc");

    private final String param;

    SearchSort(String param) {
        this.param = param;
    }

    public String param() {
        return param;
    }

    public static SearchSort fromParam(String value) {
        if (value == null || value.isBlank()) {
            return RELEVANCE;
        }
        for (SearchSort sort : values()) {
            if (sort.param.equalsIgnoreCase(value.trim())) {
                return sort;
            }
        }
        throw new BadRequestException("sort must be relevance, price_asc or price_desc");
    }
}
