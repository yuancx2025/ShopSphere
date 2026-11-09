package ecommerce.catalog;

import ecommerce.exceptions.BadRequestException;

/** Orderings the catalog page supports. Each ends in the unique id, so the order is total and stable. */
public enum CatalogSort {
    /** {@code ORDER BY id}: insertion order. Served by the primary key or (category_id, id). */
    ID("id"),
    /** {@code ORDER BY price_cents, id}. Requires a category; served by (category_id, price_cents, id). */
    PRICE_ASC("price_asc");

    private final String param;

    CatalogSort(String param) {
        this.param = param;
    }

    public String param() {
        return param;
    }

    public boolean byPrice() {
        return this != ID;
    }

    public static CatalogSort fromParam(String value) {
        if (value == null || value.isBlank()) {
            return ID;
        }
        for (CatalogSort sort : values()) {
            if (sort.param.equalsIgnoreCase(value.trim())) {
                return sort;
            }
        }
        throw new BadRequestException("sort must be id or price_asc");
    }
}
