package ecommerce.search;

/** The search backend could not answer. Mapped to 503; the catalog and checkout keep working. */
public class SearchUnavailableException extends RuntimeException {

    public SearchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
