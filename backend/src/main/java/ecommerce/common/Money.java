package ecommerce.common;

import java.math.BigDecimal;

/**
 * Exact money. Amounts are stored and summed as integer minor units (cents) and only turned into a
 * decimal at the API boundary, so an order total always equals the sum of its line items.
 */
public final class Money {

    public static final String DEFAULT_CURRENCY = "usd";

    private Money() {
    }

    /** 19.99 → 1999. Rejects amounts with more than two decimal places instead of rounding them. */
    public static long toCents(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("amount is required");
        }
        try {
            return amount.movePointRight(2).longValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("amount must have at most two decimal places: " + amount, ex);
        }
    }

    /** 1999 → 19.99 (scale 2). */
    public static BigDecimal fromCents(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }
}
