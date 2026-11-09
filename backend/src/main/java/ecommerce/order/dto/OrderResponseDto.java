package ecommerce.order.dto;

import java.math.BigDecimal;

import java.util.Date;
import java.util.List;

import ecommerce.order.model.OrderStatus;
import ecommerce.order.model.ReviewReason;

public class OrderResponseDto {

    private Integer id;
    /** Decimal dollars derived from exact cents. */
    private BigDecimal totalPrice;
    private String currency;
    private Date createdDate;
    private String sessionId;
    private OrderStatus status;
    /** Set when status is NEEDS_REVIEW. */
    private ReviewReason reviewReason;
    private List<OrderItemResponseDto> orderItems;

    public OrderResponseDto() {
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public BigDecimal getTotalPrice() {
        return totalPrice;
    }

    public void setTotalPrice(BigDecimal totalPrice) {
        this.totalPrice = totalPrice;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public ReviewReason getReviewReason() {
        return reviewReason;
    }

    public void setReviewReason(ReviewReason reviewReason) {
        this.reviewReason = reviewReason;
    }

    public Date getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(Date createdDate) {
        this.createdDate = createdDate;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public List<OrderItemResponseDto> getOrderItems() {
        return orderItems;
    }

    public void setOrderItems(List<OrderItemResponseDto> orderItems) {
        this.orderItems = orderItems;
    }
}
