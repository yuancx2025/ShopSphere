package ecommerce.order.model;

/** Why a paid order was moved to {@link OrderStatus#NEEDS_REVIEW} instead of PAID. */
public enum ReviewReason {
    /** The provider session's metadata points at a different order. */
    ORDER_MISMATCH,
    /** The order is attached to a different provider session than the one reported paid. */
    SESSION_MISMATCH,
    /** The amount the provider collected differs from the order total. */
    AMOUNT_MISMATCH,
    /** The provider collected a different currency. */
    CURRENCY_MISMATCH,
    /** Payment confirmed after the order was cancelled and its stock released (refund or re-fulfill by hand). */
    PAID_AFTER_RELEASE
}
