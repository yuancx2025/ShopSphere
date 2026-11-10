package ecommerce.order.repository;

/**
 * Current state of one order row, read with {@code SELECT ... FOR UPDATE}. A locking read returns the latest
 * committed row, unlike a managed entity that may have been loaded earlier in the transaction.
 */
public interface OrderLockView {
    Integer getId();

    Integer getUserId();

    String getStatus();

    Long getTotalCents();

    String getCurrency();

    String getSessionId();
}
