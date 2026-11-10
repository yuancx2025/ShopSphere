package ecommerce.events;

import java.time.Instant;

/**
 * The {@code ProductChanged} message body, version 1. It carries the stable event ID (the outbox row id) and which
 * product changed, not the product itself: consumers read the current MySQL row, so a late or replayed message can
 * never apply old data. Elasticsearch additionally guards with {@code search_version}.
 *
 * @param schemaVersion bumped on an incompatible change; consumers reject versions they don't know
 * @param eventId outbox row id, the same on every retry and replay; also the message key
 * @param occurredAt when the outbox row committed (MySQL clock)
 */
public record ProductChanged(int schemaVersion, long eventId, int productId, ProductChange change,
        Instant occurredAt) {

    public static final int SCHEMA_VERSION = 1;

    public static ProductChanged of(long eventId, int productId, ProductChange change, Instant occurredAt) {
        return new ProductChanged(SCHEMA_VERSION, eventId, productId, change, occurredAt);
    }
}
