package ecommerce.payment;

import java.util.List;

/**
 * Input to {@link PaymentGateway#createSession}: everything the provider needs to build a
 * checkout page, without exposing order entities or Stripe types.
 */
public class CheckoutCommand {

    private final Integer orderId;
    private final Integer userId;
    private final String customerEmail;
    private final List<LineItem> lineItems;

    public CheckoutCommand(Integer orderId, Integer userId, String customerEmail, List<LineItem> lineItems) {
        this.orderId = orderId;
        this.userId = userId;
        this.customerEmail = customerEmail;
        this.lineItems = lineItems;
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
