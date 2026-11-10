package ecommerce.order;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** How long a checkout session (and its stock reservation) stays open. Stripe requires at least 30 minutes. */
@Component
public class CheckoutProperties {

    private final Duration sessionTtl;

    public CheckoutProperties(@Value("${checkout.session-ttl:PT30M}") Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }
}
