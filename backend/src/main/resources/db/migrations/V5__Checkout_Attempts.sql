-- Sprint 1 (PR 03): checkout attempts, explicit payment outcomes, and a non-negative stock guard.

-- A checkout attempt key is bound to one user and one order. Replaying the key returns the same order and
-- provider session instead of reserving stock again.
ALTER TABLE orders ADD COLUMN checkout_key VARCHAR(64) NULL;
ALTER TABLE orders ADD COLUMN checkout_url VARCHAR(2048) NULL;
ALTER TABLE orders ADD CONSTRAINT uk_orders_user_checkout_key UNIQUE (user_id, checkout_key);

-- stock_held is true while the order's units are out of available stock (reserved or sold). It makes the
-- inventory invariant a single query: stock before checkout = available + SUM(held order quantities).
ALTER TABLE orders ADD COLUMN stock_held TINYINT(1) NOT NULL DEFAULT 0;
UPDATE orders SET stock_held = 1 WHERE status IN ('PENDING', 'PAID');

-- NEEDS_REVIEW orders record why the payment could not be applied automatically.
ALTER TABLE orders ADD COLUMN review_reason VARCHAR(64) NULL;
ALTER TABLE orders ADD COLUMN updated_date DATETIME(6) NULL;

-- Defense in depth: the database itself refuses negative stock.
ALTER TABLE products ADD CONSTRAINT chk_products_stock_nonnegative CHECK (stock >= 0);
