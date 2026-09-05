package ecommerce.payment;

/**
 * Provider-neutral payment boundary. {@link ecommerce.order.OrderService} depends on this
 * interface only; the Stripe SDK is confined to {@code ecommerce.payment.stripe}.
 */
public interface PaymentGateway {

    /** Creates a hosted checkout session and returns the redirect URL. */
    PaymentSession createSession(CheckoutCommand command);

    /** Fetches current payment status for fulfillment and reconciliation. */
    PaymentSession retrieveSession(String sessionId);

    /** Best-effort invalidation of an abandoned session (errors are swallowed). */
    void expireSession(String sessionId);

    /** Minimum charge in smallest currency unit (e.g. 50 = $0.50 USD). */
    long minimumChargeCents();

    /**
     * ISO 4217 code this gateway prices in, lower-cased. Fulfillment compares it against the
     * currency the provider reports so a session cannot settle in a cheaper currency.
     */
    String expectedCurrency();
}
