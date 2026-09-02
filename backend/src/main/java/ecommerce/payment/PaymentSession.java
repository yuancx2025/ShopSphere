package ecommerce.payment;

/**
 * Provider-neutral view of a checkout session returned by {@link PaymentGateway}.
 * {@code orderId} is copied from provider metadata so orders can be resolved even when
 * {@code sessionId} was never persisted on the order row.
 */
public class PaymentSession {

    private final String sessionId;
    /** Hosted checkout URL the browser redirects to. */
    private final String url;
    /** Provider-specific status string; use {@link #isPaid()} for fulfillment decisions. */
    private final String paymentStatus;
    private final Integer orderId;

    public PaymentSession(String sessionId, String url, String paymentStatus, Integer orderId) {
        this.sessionId = sessionId;
        this.url = url;
        this.paymentStatus = paymentStatus;
        this.orderId = orderId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUrl() {
        return url;
    }

    public String getPaymentStatus() {
        return paymentStatus;
    }

    public Integer getOrderId() {
        return orderId;
    }

    public boolean isPaid() {
        return "paid".equalsIgnoreCase(paymentStatus);
    }
}
