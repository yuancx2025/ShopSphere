package ecommerce.dto.product;


import java.math.BigDecimal;

import ecommerce.common.Money;
import ecommerce.model.Product;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class ProductDto {

    private Integer id;
    private @NotNull String name;
    private @NotNull String imageURL;
    /** Decimal dollars with at most two decimals; stored exactly as cents. */
    @NotNull
    @DecimalMin("0.00")
    @Digits(integer = 10, fraction = 2)
    private BigDecimal price;
    private @NotNull String description;
    private @NotNull Integer categoryId;
    private @NotNull @Min(0) Integer stock;

    public ProductDto(
            @NotNull String name,
            @NotNull String imageURL,
            @NotNull BigDecimal price,
            @NotNull String description,
            @NotNull Integer categoryId,
            @NotNull Integer stock) {
        this.name = name;
        this.imageURL = imageURL;
        this.price = price;
        this.description = description;
        this.categoryId = categoryId;
        this.stock = stock;
    }

    public ProductDto(Product product) {
        this.setId(product.getId());
        this.setName(product.getName());
        this.setImageURL(product.getImageURL());
        this.setDescription(product.getDescription());
        this.setPrice(Money.fromCents(product.getPriceCents()));
        this.setCategoryId(product.getCategory().getId());
        this.setStock(product.getStock());
    }

    public ProductDto() {
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getImageURL() {
        return imageURL;
    }

    public void setImageURL(String imageURL) {
        this.imageURL = imageURL;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Integer categoryId) {
        this.categoryId = categoryId;
    }

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }
}
