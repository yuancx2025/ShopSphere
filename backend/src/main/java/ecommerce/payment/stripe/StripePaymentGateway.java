package ecommerce.payment.stripe;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;

import ecommerce.payment.CheckoutCommand;
import ecommerce.payment.PaymentException;
import ecommerce.payment.PaymentGateway;
import ecommerce.payment.PaymentSession;
import jakarta.annotation.PostConstruct;

/**
 * Stripe implementation of {@link PaymentGateway}. This is the only class in the codebase
 * that imports {@code com.stripe.*}.
 */
@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "stripe", matchIfMissing = true)
public class StripePaymentGateway implements PaymentGateway {

    private final StripeProperties stripeProperties;

    public StripePaymentGateway(StripeProperties stripeProperties) {
        this.stripeProperties = stripeProperties;
    }

    @PostConstruct
    void initStripe() {
        Stripe.apiKey = stripeProperties.getSecretKey();
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
                            .setCurrency(command.getCurrency())
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
                // Fixed at reservation time: a retry must send identical parameters with the same idempotency key.
                .setExpiresAt(command.getExpiresAt().getEpochSecond());

        if (command.getCustomerEmail() != null && !command.getCustomerEmail().isBlank()) {
            params.setCustomerEmail(command.getCustomerEmail());
        }

        RequestOptions options = RequestOptions.builder()
                // Retries of the same checkout attempt return the session the first request created.
                .setIdempotencyKey(command.getIdempotencyKey())
                .build();
        try {
            return toPaymentSession(Session.create(params.build(), options));
        } catch (StripeException ex) {
            throw new PaymentException("Unable to start payment", ex);
        }
    }


    @Override
    public PaymentSession retrieveSession(String sessionId) {
        try {
            return toPaymentSession(Session.retrieve(sessionId));
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
            Session session = Session.retrieve(sessionId);
            session.expire();
        } catch (StripeException | RuntimeException ignored) {
            // Already expired, already paid, or SDK without expire().
        }
    }

    @Override
    public long minimumChargeCents() {
        return stripeProperties.getMinimumChargeCents();
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
        return new PaymentSession(session.getId(), session.getUrl(), session.getPaymentStatus(), orderId,
                session.getAmountTotal(), session.getCurrency());
    }

    private String trimBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "http://localhost:3000";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
