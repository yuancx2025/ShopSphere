package ecommerce.exceptions;

/** A replayed Idempotency-Key belongs to a checkout that already finished (HTTP 409). */
public class CheckoutAttemptClosedException extends RuntimeException {
    public CheckoutAttemptClosedException(String message) {
        super(message);
    }
}
