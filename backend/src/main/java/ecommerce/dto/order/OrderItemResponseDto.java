package ecommerce.dto.order;

public class OrderItemResponseDto {

    private Integer productId;
    private String productName;
    private String imageURL;
    private int quantity;
    private double price;

    public OrderItemResponseDto() {
    }

    public OrderItemResponseDto(
            Integer productId,
            String productName,
            String imageURL,
            int quantity,
            double price) {
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

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }
}
