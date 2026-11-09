package ecommerce.order.model;

import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import ecommerce.common.Money;
import ecommerce.model.User;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

@Entity
@Table(name="orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "created_date")
    private Date createdDate;

    /** Source of truth for the order total, in cents; always the sum of the line items. */
    @Column(name = "total_cents")
    private Long totalCents;

    /** Legacy DOUBLE column kept by Flyway V4 for rollback safety; derived from {@link #totalCents}. */
    @Column(name = "total_price")
    private Double legacyTotalPrice;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = Money.DEFAULT_CURRENCY;

    /** Client-supplied (or generated) checkout attempt key, unique per user. Replays return this order. */
    @Column(name = "checkout_key", length = 64)
    private String checkoutKey;

    /** Hosted payment page for the attached session, so a replayed attempt needs no provider call. */
    @Column(name = "checkout_url", length = 2048)
    private String checkoutUrl;

    /** True while this order's units are out of available stock (reserved or sold). */
    @Column(name = "stock_held", nullable = false)
    private boolean stockHeld;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "review_reason", length = 64)
    private ReviewReason reviewReason;

    @Column(name = "updated_date")
    private Date updatedDate;

    @Column(name = "session_id", unique = true)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 50)
    private OrderStatus status = OrderStatus.PENDING;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OrderItem> orderItems;

    @ManyToOne()
    @JsonIgnore
    @JoinColumn(name = "user_id", referencedColumnName = "id")
    private User user;

    public Order() {
    }

    public List<OrderItem> getOrderItems() {
        return orderItems;
    }

    public void setOrderItems(List<OrderItem> orderItems) {
        this.orderItems = orderItems;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }


    public Date getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(Date createdDate) {
        this.createdDate = createdDate;
    }

    public Long getTotalCents() {
        return totalCents;
    }

    public void setTotalCents(long totalCents) {
        this.totalCents = totalCents;
        this.legacyTotalPrice = totalCents / 100.0;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getCheckoutKey() {
        return checkoutKey;
    }

    public void setCheckoutKey(String checkoutKey) {
        this.checkoutKey = checkoutKey;
    }

    public String getCheckoutUrl() {
        return checkoutUrl;
    }

    public void setCheckoutUrl(String checkoutUrl) {
        this.checkoutUrl = checkoutUrl;
    }

    public boolean isStockHeld() {
        return stockHeld;
    }

    public void setStockHeld(boolean stockHeld) {
        this.stockHeld = stockHeld;
    }

    public ReviewReason getReviewReason() {
        return reviewReason;
    }

    public void setReviewReason(ReviewReason reviewReason) {
        this.reviewReason = reviewReason;
    }

    public Date getUpdatedDate() {
        return updatedDate;
    }

    public void setUpdatedDate(Date updatedDate) {
        this.updatedDate = updatedDate;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }
}
