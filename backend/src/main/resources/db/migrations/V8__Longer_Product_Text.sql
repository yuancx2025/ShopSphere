-- Sprint 3 (PR 08): room for real product text.
--
-- The relevance evaluation loads the WANDS catalog (42,994 Wayfair products). Its descriptions have a median of
-- 366 characters and run to about 4,000, and 65% are longer than the old VARCHAR(255). Truncating them would hide
-- most of the text both search engines are supposed to rank. A few names are 257 characters long.
-- The FULLTEXT index from V7 is rebuilt over the wider columns.
ALTER TABLE products
    MODIFY COLUMN name VARCHAR(512) NOT NULL,
    MODIFY COLUMN description TEXT NOT NULL;
