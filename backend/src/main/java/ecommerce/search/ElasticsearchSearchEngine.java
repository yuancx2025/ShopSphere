package ecommerce.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import ecommerce.dto.product.ProductCardDto;

/**
 * Elasticsearch engine ({@code engine=elasticsearch}). Elasticsearch ranks and filters and returns only ids; the
 * cards come from MySQL by primary key, so stock and the displayed price are always current and the JSON is the
 * same as the baseline's. Price filters run on the indexed price, which can lag an admin edit by the indexing
 * delay.
 */
@Component
@ConditionalOnProperty(name = "search.elasticsearch.enabled", havingValue = "true")
public class ElasticsearchSearchEngine implements SearchEngine {

    public static final String NAME = "elasticsearch";
    /**
     * Frozen query settings, picked on the 288 WANDS tuning queries only (benchmarks/search_eval.py tune; Sprint 3
     * report). cross_fields treats name and description as one combined field, so every query term can match in
     * either one. The name counts double.
     */
    static final List<String> FIELDS = List.of("name^2", "description");
    static final TextQueryType QUERY_TYPE = TextQueryType.CrossFields;

    private final ElasticsearchClient client;
    private final MySqlSearchEngine cards;
    private final String index;

    public ElasticsearchSearchEngine(ElasticsearchClient client, MySqlSearchEngine cards, SearchProperties props) {
        this.client = client;
        this.cards = cards;
        this.index = props.elasticsearch().index();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<ProductCardDto> search(SearchQuery query, int limit) {
        SearchResponse<Void> response;
        try {
            response = client.search(s -> {
                s.index(index)
                        .query(q -> q.bool(b -> filters(b, query)
                                .must(m -> m.multiMatch(mm -> mm.query(query.text()).fields(FIELDS).type(QUERY_TYPE)))))
                        .from(query.offset())
                        .size(limit)
                        .source(src -> src.fetch(false))
                        .trackTotalHits(t -> t.enabled(false));
                switch (query.sort()) {
                    case RELEVANCE -> s.sort(o -> o.score(sc -> sc.order(SortOrder.Desc)));
                    case PRICE_ASC -> s.sort(o -> o.field(f -> f.field("price_cents").order(SortOrder.Asc)));
                    case PRICE_DESC -> s.sort(o -> o.field(f -> f.field("price_cents").order(SortOrder.Desc)));
                }
                return s.sort(o -> o.field(f -> f.field("id").order(SortOrder.Asc)));
            }, Void.class);
        } catch (IOException | ElasticsearchException ex) {
            throw new SearchUnavailableException("Elasticsearch search failed", ex);
        }
        List<Integer> ids = new ArrayList<>(response.hits().hits().size());
        for (Hit<Void> hit : response.hits().hits()) {
            ids.add(Integer.valueOf(hit.id()));
        }
        return cards.cardsInOrder(ids);
    }

    private static BoolQuery.Builder filters(BoolQuery.Builder b, SearchQuery query) {
        if (query.categoryId() != null) {
            b.filter(f -> f.term(t -> t.field("category_id").value(query.categoryId())));
        }
        if (query.minCents() != null || query.maxCents() != null) {
            b.filter(f -> f.range(r -> {
                r.field("price_cents");
                if (query.minCents() != null) {
                    r.gte(JsonData.of(query.minCents()));
                }
                if (query.maxCents() != null) {
                    r.lte(JsonData.of(query.maxCents()));
                }
                return r;
            }));
        }
        return b;
    }
}
