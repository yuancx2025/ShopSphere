package ecommerce.payment;

/** Wraps provider failures so order code does not depend on vendor exceptions. */
public class PaymentException extends RuntimeException {

    public PaymentException(String message) {
        super(message);
    }

    public PaymentException(String message, Throwable cause) {
        super(message, cause);
    }
}
