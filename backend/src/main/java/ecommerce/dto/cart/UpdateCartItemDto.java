package ecommerce.dto.cart;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class UpdateCartItemDto {
    @NotNull
    @Min(1)
    private Integer quantity;

    public UpdateCartItemDto() {
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }
}
