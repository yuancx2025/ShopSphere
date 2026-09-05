package ecommerce.payment.stripe;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.stripe.model.checkout.Session;

import ecommerce.payment.PaymentStatus;

/**
 * Pins the translation from Stripe's two status fields onto {@link PaymentStatus}.
 *
 * <p>Runs without a database or Stripe credentials, so it guards the mapping on every build.
 */
class StripeStatusMappingTest {

    private final StripePaymentGateway gateway =
            new StripePaymentGateway(new StripeProperties("sk_test_x", "", "http://localhost:5173/", "usd"));

    @Test
    void anOpenSessionIsNotPayable() {
        assertThat(gateway.normalizeStatus(session("open", "unpaid"))).isEqualTo(PaymentStatus.CREATED);
    }

    @Test
    void aPaidSessionSucceeds() {
        assertThat(gateway.normalizeStatus(session("complete", "paid"))).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void aFullyDiscountedOrderStillSucceeds() {
        // no_payment_required means the total was zero. The old literal "paid" check missed this
        // and would have left such an order stuck in PENDING forever.
        assertThat(gateway.normalizeStatus(session("complete", "no_payment_required")))
                .isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void aCompletedButUnsettledSessionIsStillProcessing() {
        assertThat(gateway.normalizeStatus(session("complete", "unpaid"))).isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    void anExpiredSessionIsExpiredEvenBeforePaymentStatusIsConsidered() {
        assertThat(gateway.normalizeStatus(session("expired", "unpaid"))).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test
    void aSessionThatIsBothExpiredAndPaidCountsAsPaid() {
        // Stripe should never report this combination, but if it did, cancelling an order the
        // customer paid for is far worse than fulfilling a lapsed one.
        assertThat(gateway.normalizeStatus(session("expired", "paid"))).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    private Session session(String status, String paymentStatus) {
        Session session = new Session();
        session.setStatus(status);
        session.setPaymentStatus(paymentStatus);
        return session;
    }
}
