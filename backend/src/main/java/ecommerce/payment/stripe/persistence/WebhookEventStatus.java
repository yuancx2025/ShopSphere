package ecommerce.payment.stripe.persistence;

/** Lifecycle of a row in the webhook event inbox. */
public enum WebhookEventStatus {

    /** A handler owns this event and is still working on it. */
    CLAIMED,

    /** The handler finished; further deliveries of this event id are duplicates. */
    COMPLETED
}
