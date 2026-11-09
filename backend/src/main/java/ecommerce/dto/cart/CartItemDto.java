package ecommerce.dto.cart;

import ecommerce.model.Cart;
import ecommerce.dto.product.ProductDto;
import jakarta.validation.constraints.NotNull;

public class CartItemDto {
    private Integer id;
    private @NotNull Integer quantity;
    private @NotNull ProductDto product;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public ProductDto getProduct() {
        return product;
    }

    public void setProduct(ProductDto product) {
        this.product = product;
    }

    public CartItemDto(Cart cart) {
        this.setId(cart.getId());
        this.setQuantity(cart.getQuantity());
        this.setProduct(new ProductDto(cart.getProduct()));
    }
}
