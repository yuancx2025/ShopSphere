-- Sprint 5 (PR 11): Sprint 3's index-job table becomes the catalog transactional outbox.
--
-- Rows are still written in the same transaction as the catalog change. The row id is the stable event ID of the
-- ProductChanged message. Two modes read the table:
--   events.enabled=false (Sprint 3): ProductIndexer drains rows straight into Elasticsearch and deletes them.
--   events.enabled=true  (Sprint 5): OutboxPublisher sends each unpublished row to RocketMQ and sets published_at
--                                    only after the broker accepts it. Rows are kept, so the IDs can be reconciled
--                                    against consumer effects. A retry reuses the same ID.
ALTER TABLE search_index_jobs
    ADD COLUMN change_type VARCHAR(16) NOT NULL DEFAULT 'UPDATED',
    ADD COLUMN published_at DATETIME(6) NULL,
    ADD INDEX idx_search_index_jobs_unpublished (published_at, id);

-- One row per (consumer group, event) whose effect has committed. A consumer inserts its marker and applies its
-- effect in one transaction before acknowledging, so a redelivered event finds the marker and is skipped.
-- consumed_at is MySQL's clock, like search_index_jobs.created_at, so outbox-to-effect lag is one subtraction.
CREATE TABLE consumed_events (
    consumer_group VARCHAR(64) NOT NULL,
    event_id BIGINT NOT NULL,
    product_id INT NOT NULL,
    event_created_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (consumer_group, event_id)
);
