package ecommerce.payment.stripe;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Stripe keys and checkout URLs from {@code application.properties}. */
@Component
public class StripeProperties {

    private static final long DEFAULT_SESSION_TTL_SECONDS = 30 * 60;
    /** Stripe minimum for USD card charges. */
    private static final long DEFAULT_MINIMUM_CHARGE_CENTS = 50;

    private final String secretKey;
    private final String webhookSecret;
    private final String baseUrl;

    public StripeProperties(
            @Value("${STRIPE_SECRET_KEY}") String secretKey,
            @Value("${STRIPE_WEBHOOK_SECRET:}") String webhookSecret,
            @Value("${BASE_URL}") String baseUrl) {
        this.secretKey = secretKey;
        this.webhookSecret = webhookSecret;
        this.baseUrl = baseUrl;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public long getSessionTtlSeconds() {
        return DEFAULT_SESSION_TTL_SECONDS;
    }

    public long getMinimumChargeCents() {
        return DEFAULT_MINIMUM_CHARGE_CENTS;
    }
}
