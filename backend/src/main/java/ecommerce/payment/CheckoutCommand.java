package ecommerce.payment;

import java.time.Instant;
import java.util.List;

/**
 * Input to {@link PaymentGateway#createSession}: everything the provider needs to build a checkout page,
 * without exposing order entities or Stripe types.
 *
 * <p>Every field is derived from the persisted order, so retrying the same checkout attempt sends exactly the
 * same parameters with the same {@link #getIdempotencyKey() idempotency key}. Stripe rejects a reused key whose
 * parameters changed, which is why {@link #getExpiresAt()} is fixed at reservation time instead of "now + TTL".
 */
public class CheckoutCommand {

    private final Integer orderId;
    private final Integer userId;
    private final String customerEmail;
    private final List<LineItem> lineItems;
    private final long amountTotalCents;
    private final String currency;
    private final String idempotencyKey;
    private final Instant expiresAt;

    public CheckoutCommand(Integer orderId, Integer userId, String customerEmail, List<LineItem> lineItems,
            long amountTotalCents, String currency, String idempotencyKey, Instant expiresAt) {
        this.orderId = orderId;
        this.userId = userId;
        this.customerEmail = customerEmail;
        this.lineItems = List.copyOf(lineItems);
        this.amountTotalCents = amountTotalCents;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.expiresAt = expiresAt;
    }

    public Integer getOrderId() {
        return orderId;
    }

    public Integer getUserId() {
        return userId;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public List<LineItem> getLineItems() {
        return lineItems;
    }

    /** Equals the sum of the line items; the order stores the same value. */
    public long getAmountTotalCents() {
        return amountTotalCents;
    }

    public String getCurrency() {
        return currency;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public static class LineItem {
        private final Integer productId;
        private final String productName;
        private final long quantity;
        /** Price per unit in smallest currency unit (cents for USD). */
        private final long unitAmountCents;

        public LineItem(Integer productId, String productName, long quantity, long unitAmountCents) {
            this.productId = productId;
            this.productName = productName;
            this.quantity = quantity;
            this.unitAmountCents = unitAmountCents;
        }

        public Integer getProductId() {
            return productId;
        }

        public String getProductName() {
            return productName;
        }

        public long getQuantity() {
            return quantity;
        }

        public long getUnitAmountCents() {
            return unitAmountCents;
        }
    }
}
