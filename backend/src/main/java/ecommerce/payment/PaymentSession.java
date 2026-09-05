package ecommerce.payment;

/**
 * Provider-neutral view of a checkout session returned by {@link PaymentGateway}.
 * {@code orderId} is copied from provider metadata so orders can be resolved even when
 * {@code sessionId} was never persisted on the order row.
 *
 * <p>{@code status} is normalized by the adapter; order code must never inspect
 * {@code rawStatus}, which is retained only for logging and diagnostics.
 */
public class PaymentSession {

    private final String sessionId;
    /** Hosted checkout URL the browser redirects to. */
    private final String url;
    /** Provider-specific status text, kept for logs only. */
    private final String rawStatus;
    private final PaymentStatus status;
    /** Provider-reported total in the smallest currency unit, or null if not yet known. */
    private final Long amountTotalMinor;
    /** ISO 4217 code reported by the provider, lower-cased by the adapter. */
    private final String currency;
    private final Integer orderId;

    public PaymentSession(
            String sessionId,
            String url,
            PaymentStatus status,
            String rawStatus,
            Long amountTotalMinor,
            String currency,
            Integer orderId) {
        this.sessionId = sessionId;
        this.url = url;
        this.status = status;
        this.rawStatus = rawStatus;
        this.amountTotalMinor = amountTotalMinor;
        this.currency = currency;
        this.orderId = orderId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUrl() {
        return url;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getRawStatus() {
        return rawStatus;
    }

    public Long getAmountTotalMinor() {
        return amountTotalMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public Integer getOrderId() {
        return orderId;
    }

    /** True only when funds are confirmed; delayed methods stay {@link PaymentStatus#PROCESSING}. */
    public boolean isPaid() {
        return status == PaymentStatus.SUCCEEDED;
    }
}
