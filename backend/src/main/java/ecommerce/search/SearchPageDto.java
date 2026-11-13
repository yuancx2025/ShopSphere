package ecommerce.search;

import java.util.List;

import ecommerce.dto.product.ProductCardDto;

/**
 * One page of {@code GET /product/search}. There is no total count: {@code hasMore} comes from asking the engine
 * for one extra row, which costs nothing, while a count costs a second full match on MySQL.
 */
public record SearchPageDto(List<ProductCardDto> items, int page, int size, boolean hasMore, String sort,
        String engine) {
}
