package ecommerce.order.model;

public enum OrderStatus {
    PENDING,
    PAID,
    CANCELLED,
    FAILED,
    /**
     * The provider reported success for an amount or currency that does not match this order.
     * Stock stays reserved and the order is never fulfilled until a human resolves it.
     */
    PAYMENT_REVIEW
}
