package ecommerce.events;

/** What kind of catalog write produced an outbox row (the {@code change_type} column and the message tag). */
public enum ProductChange {
    CREATED,
    UPDATED,
    STOCK
}
