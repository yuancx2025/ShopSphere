package ecommerce.dto.product;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * What a product card on a catalog page shows. Same field names as {@link ProductDto}, so the React
 * {@code ProductBox} renders either.
 */
public record ProductCardDto(
        Integer id,
        String name,
        String imageURL,
        BigDecimal price,
        @JsonIgnore long priceCents,
        String description,
        Integer categoryId,
        int stock) {
}
