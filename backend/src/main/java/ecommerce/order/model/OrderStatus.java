package ecommerce.order.model;

/**
 * PENDING (stock reserved) → PAID | CANCELLED | FAILED | NEEDS_REVIEW.
 *
 * <p>NEEDS_REVIEW means money arrived but the order cannot be fulfilled automatically: the paid session did not
 * match the order ({@link ReviewReason}), or the payment confirmed after the reservation was already released.
 * An operator refunds it or fulfills it by hand; the system never quietly ignores a charge.
 */
public enum OrderStatus {
    PENDING,
    PAID,
    CANCELLED,
    FAILED,
    NEEDS_REVIEW
}
