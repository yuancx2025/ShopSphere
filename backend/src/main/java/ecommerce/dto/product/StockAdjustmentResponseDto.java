package ecommerce.dto.product;

/** Result of a stock adjustment: the applied delta and the stock right after it. */
public record StockAdjustmentResponseDto(Integer productId, int delta, int stock) {
}
