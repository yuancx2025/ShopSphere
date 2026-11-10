package ecommerce.order.dto;

import java.math.BigDecimal;

public class OrderItemResponseDto {

    private Integer productId;
    private String productName;
    private String imageURL;
    private int quantity;
    /** Unit price in decimal dollars, derived from exact cents. */
    private BigDecimal price;

    public OrderItemResponseDto() {
    }

    public OrderItemResponseDto(
            Integer productId,
            String productName,
            String imageURL,
            int quantity,
            BigDecimal price) {
        this.productId = productId;
        this.productName = productName;
        this.imageURL = imageURL;
        this.quantity = quantity;
        this.price = price;
    }

    public Integer getProductId() {
        return productId;
    }

    public void setProductId(Integer productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getImageURL() {
        return imageURL;
    }

    public void setImageURL(String imageURL) {
        this.imageURL = imageURL;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }
}
