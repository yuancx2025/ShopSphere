package ecommerce.payment.dto;

/** API response for POST /order/create-checkout-session. Frontend redirects to {@link #url}. */
public class CheckoutSessionResponseDto {
    private String sessionId;
    private String url;

    public CheckoutSessionResponseDto() {
    }

    public CheckoutSessionResponseDto(String sessionId, String url) {
        this.sessionId = sessionId;
        this.url = url;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
