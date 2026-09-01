-- Inventory reservation, order payment status, and Stripe event idempotency.

ALTER TABLE products
    ADD COLUMN stock INT NOT NULL DEFAULT 0;

-- Existing catalog rows would otherwise be unbuyable (stock 0).
UPDATE products SET stock = 100 WHERE stock = 0;

ALTER TABLE orders
    ADD COLUMN status VARCHAR(50) NOT NULL DEFAULT 'PENDING';

ALTER TABLE orders
    ADD CONSTRAINT uk_orders_session_id UNIQUE (session_id);

ALTER TABLE orderitems
    ADD COLUMN product_name VARCHAR(255) NULL;

UPDATE orderitems oi
    INNER JOIN products p ON oi.product_id = p.id
    SET oi.product_name = p.name
    WHERE oi.product_name IS NULL;

UPDATE orderitems
    SET product_name = 'Unknown product'
    WHERE product_name IS NULL;

ALTER TABLE orderitems
    MODIFY product_name VARCHAR(255) NOT NULL;

CREATE TABLE processed_stripe_events (
    event_id VARCHAR(255) NOT NULL,
    processed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (event_id)
);
