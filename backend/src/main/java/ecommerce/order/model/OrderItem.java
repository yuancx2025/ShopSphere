package ecommerce.order.model;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonIgnore;

import ecommerce.model.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "orderitems")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "quantity")
    private @NotNull int quantity;

    /** Unit price in cents at reservation time. */
    @Column(name = "unit_price_cents", nullable = false)
    private long unitPriceCents;

    /** Legacy DOUBLE column kept by Flyway V4 for rollback safety; derived from {@link #unitPriceCents}. */
    @Column(name = "price", nullable = false)
    private double legacyPrice;

    @Column(name = "product_name")
    private @NotNull String productName;

    @Column(name = "created_date")
    private Date createdDate;

    @ManyToOne
    @JsonIgnore
    @JoinColumn(name = "order_id", referencedColumnName = "id")
    private Order order;

    @ManyToOne
    @JoinColumn(name = "product_id", referencedColumnName = "id")
    private Product product;

    public OrderItem(){}

    public OrderItem(Order order, @NotNull Product product, int quantity, long unitPriceCents) {
        this.product = product;
        this.quantity = quantity;
        setUnitPriceCents(unitPriceCents);
        this.productName = product.getName();
        this.order = order;
        this.createdDate = new Date();
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }


    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public long getUnitPriceCents() {
        return unitPriceCents;
    }

    public void setUnitPriceCents(long unitPriceCents) {
        this.unitPriceCents = unitPriceCents;
        this.legacyPrice = unitPriceCents / 100.0;
    }

    public long getLineTotalCents() {
        return Math.multiplyExact(unitPriceCents, (long) quantity);
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public Date getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(Date createdDate) {
        this.createdDate = createdDate;
    }

    public Order getOrder() {
        return order;
    }

    public void setOrder(Order order) {
        this.order = order;
    }
}
