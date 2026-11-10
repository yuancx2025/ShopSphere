package ecommerce.search;

import java.util.List;

import ecommerce.dto.product.ProductCardDto;

/** One way to answer a {@link SearchQuery}. */
public interface SearchEngine {

    /** The value of the {@code engine} request parameter that selects this engine. */
    String name();

    /** Returns up to {@code limit} cards starting at {@code query.offset()}, in the query's order. */
    List<ProductCardDto> search(SearchQuery query, int limit);
}
