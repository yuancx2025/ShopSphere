package ecommerce.search;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import ecommerce.common.Money;
import ecommerce.dto.product.ProductCardDto;

/**
 * The MySQL FULLTEXT baseline ({@code engine=mysql}): natural-language {@code MATCH ... AGAINST} over the V7
 * index on (name, description). The SQL is built only from the fixed fragments below; user input is always a
 * bound parameter. Also loads cards by id for the Elasticsearch engine, so both engines return the same JSON.
 */
@Component
public class MySqlSearchEngine implements SearchEngine {

    public static final String NAME = "mysql";

    static final String MATCH = "MATCH(name, description) AGAINST (:q IN NATURAL LANGUAGE MODE)";
    static final String CARD_COLUMNS = "id, name, image_url, price_cents, description, category_id, stock";

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

    public MySqlSearchEngine(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<ProductCardDto> search(SearchQuery query, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("q", query.text())
                .addValue("limit", limit)
                .addValue("offset", query.offset());
        StringBuilder sql = new StringBuilder("SELECT ").append(CARD_COLUMNS).append(", ").append(MATCH)
                .append(" AS score FROM products WHERE ").append(MATCH);
        if (query.categoryId() != null) {
            sql.append(" AND category_id = :categoryId");
            params.addValue("categoryId", query.categoryId());
        }
        if (query.minCents() != null) {
            sql.append(" AND price_cents >= :minCents");
            params.addValue("minCents", query.minCents());
        }
        if (query.maxCents() != null) {
            sql.append(" AND price_cents <= :maxCents");
            params.addValue("maxCents", query.maxCents());
        }
        sql.append(switch (query.sort()) {
            case RELEVANCE -> " ORDER BY score DESC, id";
            case PRICE_ASC -> " ORDER BY price_cents, id";
            case PRICE_DESC -> " ORDER BY price_cents DESC, id";
        });
        sql.append(" LIMIT :limit OFFSET :offset");
        return jdbc.query(sql.toString(), params, CARD);
    }

    /** Cards for {@code ids}, in the order given. Ids with no product (deleted since indexing) are skipped. */
    public List<ProductCardDto> cardsInOrder(List<Integer> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Integer, ProductCardDto> byId = new HashMap<>();
        for (ProductCardDto card : jdbc.query("SELECT " + CARD_COLUMNS + " FROM products WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids), CARD)) {
            byId.put(card.id(), card);
        }
        List<ProductCardDto> ordered = new ArrayList<>(ids.size());
        for (Integer id : ids) {
            ProductCardDto card = byId.get(id);
            if (card != null) {
                ordered.add(card);
            }
        }
        return ordered;
    }

    /** Rows for the indexer: the searchable fields plus the version that guards against stale overwrites. */
    public List<Map<String, Object>> documents(Collection<Integer> productIds) {
        return jdbc.queryForList("SELECT id, name, description, category_id, price_cents, search_version"
                + " FROM products WHERE id IN (:ids)", new MapSqlParameterSource("ids", productIds));
    }
}
