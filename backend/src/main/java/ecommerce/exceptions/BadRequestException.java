package ecommerce.exceptions;

/** Malformed client input that bean validation cannot express, such as an invalid page cursor (HTTP 400). */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
