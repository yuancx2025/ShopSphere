-- Flyway V2: webhook inbox states and a sweeper-friendly order index.

-- Claim-first webhook processing. Any row that already exists was written by the previous
-- record-after-success logic, so it represents finished work.
ALTER TABLE processed_stripe_events
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    ADD COLUMN claimed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- processed_at is now set only when a claim completes, so it must be nullable.
ALTER TABLE processed_stripe_events
    ALTER COLUMN processed_at DROP NOT NULL,
    ALTER COLUMN processed_at DROP DEFAULT;

-- Lets the reconciliation sweeper find stale PENDING orders by age without a full scan.
CREATE INDEX idx_orders_status_created ON orders (status, created_date);
