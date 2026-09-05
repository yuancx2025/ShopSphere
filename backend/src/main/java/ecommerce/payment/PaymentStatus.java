package ecommerce.payment;

/**
 * Provider-neutral payment lifecycle. Adapters translate their own vocabulary into these
 * values so order code never compares provider status strings.
 */
public enum PaymentStatus {

    /** Session exists and is still awaiting the customer. */
    CREATED,

    /** Customer completed checkout but funds are not confirmed yet (delayed payment methods). */
    PROCESSING,

    /** Funds are confirmed, or no payment was required. Safe to fulfill. */
    SUCCEEDED,

    /** The provider reported a terminal failure. */
    FAILED,

    /** The session lapsed before the customer paid. */
    EXPIRED
}
