package ecommerce.payment.stripe;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;

import ecommerce.order.OrderService;
import ecommerce.order.model.OrderStatus;

/**
 * Entry point for Stripe server-to-server notifications. Public (no JWT); authenticity is
 * verified via {@code Stripe-Signature}. Delegates business rules to {@link OrderService}.
 */
@RestController
@RequestMapping("/webhook")
public class StripeWebhookController {

    private final OrderService orderService;
    private final StripeEventService stripeEventService;
    private final StripeProperties stripeProperties;

    public StripeWebhookController(
            OrderService orderService,
            StripeEventService stripeEventService,
            StripeProperties stripeProperties) {
        this.orderService = orderService;
        this.stripeEventService = stripeEventService;
        this.stripeProperties = stripeProperties;
    }

    @PostMapping("/stripe")
    public ResponseEntity<String> handleStripeEvent(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String stripeSignature) {
        Event event;
        try {
            event = Webhook.constructEvent(payload, stripeSignature, stripeProperties.getWebhookSecret());
        } catch (SignatureVerificationException | IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid signature");
        }

        if (stripeEventService.alreadyProcessed(event.getId())) {
            return ResponseEntity.ok("ok");
        }

        try {
            switch (event.getType()) {
                case "checkout.session.completed":
                case "checkout.session.async_payment_succeeded":
                    orderService.fulfillCheckout(requireSessionId(event));
                    break;
                case "checkout.session.expired":
                    orderService.releaseCheckout(requireSessionId(event), OrderStatus.CANCELLED);
                    break;
                case "checkout.session.async_payment_failed":
                    orderService.releaseCheckout(requireSessionId(event), OrderStatus.FAILED);
                    break;
                default:
                    return ResponseEntity.ok("ignored");
            }
            // Record only after order logic succeeds so Stripe retries on transient failures.
            stripeEventService.recordProcessedEvent(event.getId());
            return ResponseEntity.ok("ok");
        } catch (RuntimeException ex) {
            // 500 tells Stripe to retry; do not record the event id yet.
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("retry");
        }
    }

    private String requireSessionId(Event event) {
        StripeObject stripeObject = event.getDataObjectDeserializer().getObject().orElse(null);
        if (stripeObject instanceof Session session && session.getId() != null) {
            return session.getId();
        }
        // Some API versions need the unsafe deserializer to populate the session object.
        try {
            StripeObject unsafe = event.getDataObjectDeserializer().deserializeUnsafe();
            if (unsafe instanceof Session session && session.getId() != null) {
                return session.getId();
            }
        } catch (Exception ignored) {
            // Fall through to a retryable error.
        }
        throw new IllegalStateException("Unable to read Checkout Session from event");
    }
}
