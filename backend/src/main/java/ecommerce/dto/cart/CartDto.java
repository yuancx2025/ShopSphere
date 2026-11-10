package ecommerce.dto.cart;

import java.math.BigDecimal;

import java.util.List;

public class CartDto {

    //list of each item
    private List<CartItemDto> cartItems;

    // total cost for the cart, decimal dollars from exact cents
    private BigDecimal totalCost;

    public CartDto(List<CartItemDto> cartItemDtoList, BigDecimal totalCost) {
        this.cartItems = cartItemDtoList;
        this.totalCost = totalCost;
    }

    public List<CartItemDto> getcartItems() {
        return cartItems;
    }

    public void setCartItems(List<CartItemDto> cartItemDtoList) {
        this.cartItems = cartItemDtoList;
    }

    public BigDecimal getTotalCost() {
        return totalCost;
    }

    public void setTotalCost(BigDecimal totalCost) {
        this.totalCost = totalCost;
    }
}
