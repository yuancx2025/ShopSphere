-- Sprint 1 (PR 03): exact money.
--
-- Integer cents become the source of truth. The legacy DOUBLE columns stay and the application keeps writing
-- them (derived from cents), so rolling back to the previous release still reads valid prices. A later
-- migration can drop them once no rollback target needs them.
--
-- Each block backfills, then validates the legacy rows with a temporary CHECK constraint. MySQL 8 refuses to
-- add a CHECK that an existing row violates, so a legacy price with sub-cent precision (say 19.999) fails this
-- migration by name instead of being rounded silently. DDL auto-commits in MySQL, so a failure here leaves the
-- added column in place; fix the offending rows and re-run (see flyway repair).

-- products.price → products.price_cents
ALTER TABLE products ADD COLUMN price_cents BIGINT NULL AFTER price;
UPDATE products SET price_cents = ROUND(price * 100);
ALTER TABLE products ADD CONSTRAINT chk_v4_products_price_whole_cents
    CHECK (ABS(price * 100 - price_cents) < 0.0001);
ALTER TABLE products DROP CHECK chk_v4_products_price_whole_cents;
ALTER TABLE products MODIFY price_cents BIGINT NOT NULL;
ALTER TABLE products ADD CONSTRAINT chk_products_price_cents_nonnegative CHECK (price_cents >= 0);

-- orderitems.price → orderitems.unit_price_cents
ALTER TABLE orderitems ADD COLUMN unit_price_cents BIGINT NULL AFTER price;
UPDATE orderitems SET unit_price_cents = ROUND(price * 100);
ALTER TABLE orderitems ADD CONSTRAINT chk_v4_orderitems_price_whole_cents
    CHECK (ABS(price * 100 - unit_price_cents) < 0.0001);
ALTER TABLE orderitems DROP CHECK chk_v4_orderitems_price_whole_cents;
ALTER TABLE orderitems MODIFY unit_price_cents BIGINT NOT NULL;

-- orders.total_price → orders.total_cents (+ currency). Legacy totals were summed from doubles, so they carry
-- float noise (59.97000000000001) but no real sub-cent amounts; the tolerance below is far smaller than a cent.
ALTER TABLE orders ADD COLUMN total_cents BIGINT NULL AFTER total_price;
ALTER TABLE orders ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'usd' AFTER total_cents;
UPDATE orders SET total_cents = ROUND(total_price * 100) WHERE total_price IS NOT NULL;
ALTER TABLE orders ADD CONSTRAINT chk_v4_orders_total_whole_cents
    CHECK (total_price IS NULL OR ABS(total_price * 100 - total_cents) < 0.0001);
ALTER TABLE orders DROP CHECK chk_v4_orders_total_whole_cents;
