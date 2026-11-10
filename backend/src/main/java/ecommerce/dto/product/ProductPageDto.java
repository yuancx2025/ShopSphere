package ecommerce.dto.product;

import java.util.List;

/**
 * One page of {@code GET /product/page}. {@code nextCursor} is null on the last page; otherwise pass it back as
 * {@code cursor} with the same {@code sort} and {@code categoryId} to get the next page.
 */
public record ProductPageDto(List<ProductCardDto> items, String nextCursor, int size, String sort,
        Integer categoryId) {
}
