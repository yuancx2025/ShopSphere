-- Sprint 3 (PR 07): product search.
--
-- 1. MySQL FULLTEXT baseline. GET /product/search?engine=mysql runs
--      MATCH(name, description) AGAINST (? IN NATURAL LANGUAGE MODE)
--    InnoDB defaults apply: tokens shorter than 3 characters and the built-in stopword list are not indexed.
CREATE FULLTEXT INDEX ft_products_name_description ON products (name, description);

-- 2. Search version. Bumped on every admin write that changes a searchable field. The indexer sends it to
--    Elasticsearch as an external version, so an older snapshot (a replayed job, or a backfill that read the row
--    before an edit) can never overwrite a newer document.
ALTER TABLE products ADD COLUMN search_version BIGINT NOT NULL DEFAULT 1;

-- 3. Index jobs. Admin catalog writes insert a row in the same transaction as the product change, so a committed
--    change always has a job and a rolled-back one never does. The indexer reads the current product row for each
--    job, upserts it into Elasticsearch, and deletes the job. A backfill is
--      INSERT INTO search_index_jobs (product_id) SELECT id FROM products;
CREATE TABLE search_index_jobs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    attempts INT NOT NULL DEFAULT 0
);
