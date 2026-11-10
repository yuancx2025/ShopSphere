package ecommerce.search;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import ecommerce.dto.product.ProductCardDto;
import ecommerce.exceptions.BadRequestException;

/** {@code GET /product/search}: validates the request once and hands the same query to the chosen engine. */
@Service
public class ProductSearchService {

    private final Map<String, SearchEngine> engines = new HashMap<>();
    private final String defaultEngine;

    public ProductSearchService(List<SearchEngine> engines, SearchProperties props) {
        for (SearchEngine engine : engines) {
            this.engines.put(engine.name(), engine);
        }
        this.defaultEngine = props.engine();
    }

    /**
     * @param engineParam {@code mysql} or {@code elasticsearch}; null uses {@code search.engine}. The parameter
     *        lets the relevance evaluation and the load test compare both engines on one running stack.
     */
    public SearchPageDto search(SearchQuery query, String engineParam) {
        String name = engineParam == null || engineParam.isBlank() ? defaultEngine : engineParam.trim().toLowerCase();
        SearchEngine engine = engines.get(name);
        if (engine == null) {
            throw new BadRequestException("engine must be one of " + engines.keySet());
        }
        List<ProductCardDto> rows = engine.search(query, query.size() + 1);
        boolean hasMore = rows.size() > query.size();
        List<ProductCardDto> items = hasMore ? rows.subList(0, query.size()) : rows;
        return new SearchPageDto(List.copyOf(items), query.page(), query.size(), hasMore, query.sort().param(),
                engine.name());
    }
}
