package ecommerce.payment.stripe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;

import ecommerce.payment.CheckoutCommand;
import ecommerce.payment.PaymentException;
import ecommerce.payment.PaymentGateway;
import ecommerce.payment.PaymentSession;
import ecommerce.payment.PaymentStatus;

/**
 * Stripe implementation of {@link PaymentGateway}. This is the only class in the codebase
 * that imports {@code com.stripe.*}.
 *
 * <p>Calls go through an injected {@link StripeClient} rather than the global {@code Stripe.apiKey}
 * and static model methods, so credentials are per-instance and the gateway can be constructed with
 * different configuration in a test.
 */
@Component
public class StripePaymentGateway implements PaymentGateway {

    private final StripeProperties stripeProperties;
    private final StripeClient stripeClient;

    public StripePaymentGateway(StripeProperties stripeProperties) {
        this.stripeProperties = stripeProperties;
        this.stripeClient = StripeClient.builder()
                .setApiKey(stripeProperties.getSecretKey())
                .build();
    }

    @Override
    public PaymentSession createSession(CheckoutCommand command) {
        // Stripe replaces {CHECKOUT_SESSION_ID} when redirecting back to the SPA.
        String successURL = trimBaseUrl(stripeProperties.getBaseUrl())
                + "/payment/success?session_id={CHECKOUT_SESSION_ID}";
        String failedURL = trimBaseUrl(stripeProperties.getBaseUrl()) + "/payment/failed";

        List<SessionCreateParams.LineItem> lineItems = new ArrayList<>();
        for (CheckoutCommand.LineItem item : command.getLineItems()) {
            lineItems.add(SessionCreateParams.LineItem.builder()
                    .setQuantity(item.getQuantity())
                    .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                            .setCurrency(stripeProperties.getCurrency())
                            .setUnitAmount(item.getUnitAmountCents())
                            .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                    .setName(item.getProductName())
                                    .putMetadata(
                                            "productId",
                                            item.getProductId() == null ? "" : String.valueOf(item.getProductId()))
                                    .build())
                            .build())
                    .build());
        }

        SessionCreateParams.Builder params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setCancelUrl(failedURL)
                .setSuccessUrl(successURL)
                .addAllLineItem(lineItems)
                .setClientReferenceId(String.valueOf(command.getUserId()))
                // metadata.orderId lets OrderService find the order if sessionId was never saved
                .putMetadata("orderId", String.valueOf(command.getOrderId()))
                .putMetadata("userId", String.valueOf(command.getUserId()))
                .setExpiresAt(Instant.now().plusSeconds(stripeProperties.getSessionTtlSeconds()).getEpochSecond());

        if (command.getCustomerEmail() != null && !command.getCustomerEmail().isBlank()) {
            params.setCustomerEmail(command.getCustomerEmail());
        }

        RequestOptions options = RequestOptions.builder()
                // Retries with the same key return the same session instead of double-charging.
                .setIdempotencyKey("checkout-order-" + command.getOrderId())
                .build();
        try {
            return toPaymentSession(stripeClient.checkout().sessions().create(params.build(), options));
        } catch (StripeException ex) {
            throw new PaymentException("Unable to start payment", ex);
        }
    }

    @Override
    public PaymentSession retrieveSession(String sessionId) {
        try {
            return toPaymentSession(stripeClient.checkout().sessions().retrieve(sessionId));
        } catch (StripeException ex) {
            throw new PaymentException("Unable to retrieve payment session", ex);
        }
    }

    @Override
    public void expireSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            stripeClient.checkout().sessions().expire(sessionId);
        } catch (StripeException | RuntimeException ignored) {
            // Already expired or already paid; nothing to invalidate.
        }
    }

    @Override
    public long minimumChargeCents() {
        return stripeProperties.getMinimumChargeCents();
    }

    @Override
    public String expectedCurrency() {
        return stripeProperties.getCurrency();
    }

    private PaymentSession toPaymentSession(Session session) {
        Integer orderId = null;
        if (session.getMetadata() != null) {
            String raw = session.getMetadata().get("orderId");
            if (raw != null && !raw.isBlank()) {
                try {
                    orderId = Integer.valueOf(raw);
                } catch (NumberFormatException ignored) {
                    // OrderService can still resolve by session id when metadata is malformed.
                }
            }
        }
        String currency = session.getCurrency() == null ? null : session.getCurrency().toLowerCase();
        String rawStatus = session.getStatus() + "/" + session.getPaymentStatus();
        return new PaymentSession(
                session.getId(),
                session.getUrl(),
                normalizeStatus(session),
                rawStatus,
                session.getAmountTotal(),
                currency,
                orderId);
    }

    /**
     * Maps Stripe's two status fields onto {@link PaymentStatus}.
     *
     * <p>Session {@code status} is {@code open}, {@code complete}, or {@code expired}.
     * {@code payment_status} is {@code paid}, {@code unpaid}, or {@code no_payment_required};
     * the latter covers fully discounted orders, which must still fulfill.
     *
     * <p>Payment is checked before expiry on purpose. Stripe should never report a session that is
     * both expired and paid, but if it did, treating it as expired would cancel the order and
     * restore stock for a customer whose money was actually taken.
     */
    // Package-private so the mapping can be unit-tested without a Stripe account or a database.
    PaymentStatus normalizeStatus(Session session) {
        String sessionStatus = session.getStatus();
        String paymentStatus = session.getPaymentStatus();

        if ("paid".equalsIgnoreCase(paymentStatus) || "no_payment_required".equalsIgnoreCase(paymentStatus)) {
            return PaymentStatus.SUCCEEDED;
        }
        if ("expired".equalsIgnoreCase(sessionStatus)) {
            return PaymentStatus.EXPIRED;
        }
        if ("complete".equalsIgnoreCase(sessionStatus)) {
            // Checkout finished but a delayed method has not settled yet.
            return PaymentStatus.PROCESSING;
        }
        return PaymentStatus.CREATED;
    }

    private String trimBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "http://localhost:3000";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
