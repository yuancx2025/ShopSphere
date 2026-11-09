package ecommerce.catalog;

import java.util.List;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import ecommerce.common.Money;
import ecommerce.dto.product.ProductCardDto;

/**
 * The catalog page queries, written out as SQL so the query contract is explicit and the benchmark's
 * {@code bench.py explain} can run EXPLAIN ANALYZE on the same statements (keep
 * {@code explain_queries} in {@code benchmarks/bench.py} in sync).
 *
 * <p>Keyset pagination: each page continues strictly after the last row of the previous one, so a page costs
 * the same however deep the client scrolls, and inserts never shift rows between pages. The projection selects
 * only the columns a product card shows and maps rows straight to DTOs, without loading entities.
 */
@Repository
public class CatalogQueries {

    static final String COLUMNS = "SELECT id, name, image_url, price_cents, description, category_id, stock FROM products";

    static final String BY_ID = COLUMNS
            + " WHERE id > :afterId ORDER BY id LIMIT :limit";
    /**
     * The one query with an index hint, because MySQL's own choice here depends on the cursor value. For
     * {@code category_id = ? AND id > ? ORDER BY id LIMIT 21} the optimizer picked, on the same data, a primary
     * key scan (about 250 rows examined for 21 at the start of a category), the right range scan (21 rows) in the
     * middle, and an index-merge intersection plus a sort near the end. idx_product_category is (category_id)
     * plus the primary key that InnoDB appends, so it already holds the rows in (category_id, id) order; the hint
     * pins that plan for every cursor. {@code bench.py explain} records the plans with and without the hint.
     */
    static final String CATEGORY_BY_ID = "SELECT /*+ INDEX(products idx_product_category) */ "
            + "id, name, image_url, price_cents, description, category_id, stock FROM products"
            + " WHERE category_id = :categoryId AND id > :afterId ORDER BY id LIMIT :limit";
    static final String CATEGORY_BY_PRICE_ASC_FIRST = COLUMNS
            + " WHERE category_id = :categoryId ORDER BY price_cents, id LIMIT :limit";
    static final String CATEGORY_BY_PRICE_ASC_AFTER = COLUMNS
            + " WHERE category_id = :categoryId"
            + " AND (price_cents > :afterPrice OR (price_cents = :afterPrice AND id > :afterId))"
            + " ORDER BY price_cents, id LIMIT :limit";

    private static final RowMapper<ProductCardDto> CARD = (rs, rowNum) -> new ProductCardDto(
            rs.getInt("id"),
            rs.getString("name"),
            rs.getString("image_url"),
            Money.fromCents(rs.getLong("price_cents")),
            rs.getLong("price_cents"),
            rs.getString("description"),
            rs.getInt("category_id"),
            rs.getInt("stock"));

    private final NamedParameterJdbcTemplate jdbc;

    public CatalogQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns up to {@code limit} cards after {@code cursor} (null for the first page). */
    public List<ProductCardDto> page(Integer categoryId, CatalogSort sort, CatalogCursor cursor, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("limit", limit)
                .addValue("categoryId", categoryId)
                .addValue("afterId", cursor == null ? 0 : cursor.id())
                .addValue("afterPrice", cursor == null ? 0 : cursor.priceCents());
        return jdbc.query(sql(categoryId != null, sort, cursor != null), params, CARD);
    }

    static String sql(boolean byCategory, CatalogSort sort, boolean hasCursor) {
        return switch (sort) {
            case ID -> byCategory ? CATEGORY_BY_ID : BY_ID;
            case PRICE_ASC -> hasCursor ? CATEGORY_BY_PRICE_ASC_AFTER : CATEGORY_BY_PRICE_ASC_FIRST;
        };
    }
}
