package ecommerce.order;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ecommerce.dto.cart.CartDto;
import ecommerce.dto.cart.CartItemDto;
import ecommerce.exceptions.CustomException;
import ecommerce.exceptions.InsufficientStockException;
import ecommerce.exceptions.OrderNotFoundException;
import ecommerce.model.Product;
import ecommerce.model.User;
import ecommerce.order.dto.OrderItemResponseDto;
import ecommerce.order.dto.OrderResponseDto;
import ecommerce.order.model.Order;
import ecommerce.order.model.OrderItem;
import ecommerce.order.model.OrderStatus;
import ecommerce.order.repository.OrderRepository;
import ecommerce.payment.CheckoutCommand;
import ecommerce.payment.PaymentException;
import ecommerce.payment.PaymentGateway;
import ecommerce.payment.PaymentSession;
import ecommerce.payment.dto.CheckoutSessionResponseDto;
import ecommerce.repository.ProductRepository;
import ecommerce.repository.UserRepository;
import ecommerce.service.CartService;

/**
 * Order domain service: stock reservation, order lifecycle, and checkout orchestration.
 *
 * <p>Payment provider details live behind {@link PaymentGateway}; this class never imports Stripe.
 * Fulfillment can happen from two places:
 * <ul>
 *   <li>{@link #fulfillCheckout} — called by the Stripe webhook (trusted, no user ownership check)</li>
 *   <li>{@link #reconcileCheckout} — called by the success page when the webhook is delayed</li>
 * </ul>
 *
 * <p>Order status flow: PENDING (stock reserved) → PAID | CANCELLED | FAILED.
 */
@Service
public class OrderService {

    // A caller must not be able to tell an unknown session from someone else's session.
    private static final String ORDER_NOT_FOUND = "order id is not valid";

    private final CartService cartService;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final PaymentGateway paymentGateway;
    private final TransactionTemplate transactionTemplate;

    public OrderService(
            CartService cartService,
            OrderRepository orderRepository,
            ProductRepository productRepository,
            UserRepository userRepository,
            PaymentGateway paymentGateway,
            PlatformTransactionManager transactionManager) {
        this.cartService = cartService;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.paymentGateway = paymentGateway;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // -------------------------------------------------------------------------
    // Checkout start: reserve stock, create a hosted payment session, return URL
    // -------------------------------------------------------------------------

    public CheckoutSessionResponseDto createSession(User user) throws CustomException {
        // Collect old Stripe session ids outside the DB transaction; expire them after commit.
        List<String> sessionsToExpire = new ArrayList<>();
        Order order;
        try {
            order = transactionTemplate.execute(status -> {
                // Serialize checkout per user so two tabs cannot reserve the same stock twice.
                userRepository.findByIdForUpdate(user.getId());
                for (Order previous : orderRepository.findAllByUserAndStatus(user, OrderStatus.PENDING)) { // Cancel all pending orders for the user.
                    if (previous.getSessionId() != null) {
                        sessionsToExpire.add(previous.getSessionId());
                    }
                    restoreStock(previous.getId(), OrderStatus.CANCELLED);
                }
                return reserveFromCart(user);
            });
        } catch (IllegalStateException ex) {
            if ("EMPTY_CART".equals(ex.getMessage())) {
                throw new CustomException("Cart is empty");
            }
            if ("MIN_CHARGE".equals(ex.getMessage())) {
                throw new CustomException("Order total must be at least $0.50");
            }
            throw ex;
        }
        if (order == null) {
            throw new CustomException("Unable to reserve cart");
        }
        sessionsToExpire.forEach(paymentGateway::expireSession);

        // create the stripe session and return the url
        try {  
            PaymentSession session = paymentGateway.createSession(toCheckoutCommand(order));
            try {
                transactionTemplate.executeWithoutResult(status -> attachSessionId(order.getId(), session.getSessionId()));
            } catch (RuntimeException ignored) {
                // attachSessionId can fail after Stripe already created the session.
                // reconcileCheckout / fulfillCheckout can still find the order via metadata.orderId.
            }
            return new CheckoutSessionResponseDto(session.getSessionId(), session.getUrl());
        } catch (PaymentException ex) {
            // Stripe session was never created; release the reserved stock.
            releaseCheckoutByOrderId(order.getId(), OrderStatus.CANCELLED);
            throw new CustomException("Unable to start payment");
        }
    }

    // -------------------------------------------------------------------------
    // Fulfillment & release (called by StripeWebhookController and reconcileCheckout)
    // -------------------------------------------------------------------------

    /**
     * Marks an order PAID when Stripe reports payment succeeded.
     * Webhook-only path: caller is signature-verified, so there is no user ownership check.
     */
    public OrderResponseDto fulfillCheckout(String sessionId) {
        PaymentSession session = paymentGateway.retrieveSession(sessionId);
        Order order = resolveOrder(session);
        if (order == null) {
            return null;
        }
        if (order.getStatus() == OrderStatus.PAID) {
            return toDto(reloadWithItems(order.getId()));
        }
        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.FAILED) {
            return toDto(reloadWithItems(order.getId()));
        }
        if (!session.isPaid()) {
            // Webhook can arrive before Stripe marks the session paid; return current state.
            return toDto(reloadWithItems(order.getId()));
        }

        Order paid = transactionTemplate.execute(status -> markPaid(order.getId()));
        return toDto(paid);
    }

    /** Restores reserved stock when checkout expires or async payment fails. */
    public void releaseCheckout(String sessionId, OrderStatus terminalStatus) {
        orderRepository.findBySessionIdWithItems(sessionId)
                .ifPresent(order -> releaseCheckoutByOrderId(order.getId(), terminalStatus));
    }

    public void releaseCheckoutByOrderId(Integer orderId, OrderStatus terminalStatus) {
        transactionTemplate.executeWithoutResult(status -> restoreStock(orderId, terminalStatus));
    }

    // -------------------------------------------------------------------------
    // Read APIs (no side effects; used by order history and success-page polling)
    // -------------------------------------------------------------------------

    public List<OrderResponseDto> listOrders(User user) {
        // PENDING orders are in-flight checkouts; hide them from history until they settle.
        List<Order> orders = orderRepository.findAllVisibleWithItems(user, OrderStatus.PENDING);
        orders.sort(Comparator.comparing(Order::getCreatedDate, Comparator.nullsLast(Comparator.reverseOrder())));
        return orders.stream().map(this::toDto).collect(Collectors.toList());
    }

    public OrderResponseDto getOrder(Integer orderId, User user) throws OrderNotFoundException {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new OrderNotFoundException(ORDER_NOT_FOUND));
        requireOwner(order, user);
        return toDto(order);
    }

    /** Read-only lookup for the success page poll loop. Never calls Stripe or mutates state. */
    public OrderResponseDto getOrderForSession(String sessionId, User user) throws OrderNotFoundException {
        Order order = orderRepository.findBySessionIdWithItems(sessionId)
                .orElseThrow(() -> new OrderNotFoundException(ORDER_NOT_FOUND));
        requireOwner(order, user);
        return toDto(order);
    }

    /**
     * User-triggered recovery when the webhook is delayed or missing (e.g. local dev without
     * {@code stripe listen}). Ownership is checked before any write or Stripe call that could
     * mark an order paid.
     */
    public OrderResponseDto reconcileCheckout(String sessionId, User user) throws OrderNotFoundException {
        Order order = orderRepository.findBySessionIdWithItems(sessionId).orElse(null);
        PaymentSession session = null;

        if (order == null) {
            // The session id was never persisted, so provider metadata is the only link back to the order.
            session = paymentGateway.retrieveSession(sessionId);
            if (session.getOrderId() == null) {
                throw new OrderNotFoundException(ORDER_NOT_FOUND);
            }
            order = orderRepository.findByIdWithItems(session.getOrderId())
                    .orElseThrow(() -> new OrderNotFoundException(ORDER_NOT_FOUND));
        }

        requireOwner(order, user);

        // Backfill session id when attachSessionId failed at checkout time.
        if (order.getSessionId() == null) {
            healSessionId(order.getId(), sessionId);
            order = reloadWithItems(order.getId());
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            return toDto(order);
        }
        if (session == null) {
            session = paymentGateway.retrieveSession(sessionId);
        }
        if (!session.isPaid()) {
            return toDto(order);
        }

        Integer orderId = order.getId();
        return toDto(transactionTemplate.execute(status -> markPaid(orderId)));
    }

    /** Same error text for missing and foreign orders so callers cannot probe session ids. */
    private void requireOwner(Order order, User user) throws OrderNotFoundException {
        if (order.getUser() == null || !order.getUser().getId().equals(user.getId())) {
            throw new OrderNotFoundException(ORDER_NOT_FOUND);
        }
    }

    // -------------------------------------------------------------------------
    // Stock reservation & order persistence
    // -------------------------------------------------------------------------

    /**
     * Converts the user's cart into a PENDING order and decrements product stock.
     * Must run inside a transaction with product rows locked ({@code findAllByIdForUpdate}).
     * Cart is not cleared here; that happens in {@link #markPaid} after payment succeeds.
     */
    private Order reserveFromCart(User user) {
        CartDto cartDto = cartService.listCartItems(user);
        List<CartItemDto> cartItems = cartDto.getcartItems();
        if (cartItems == null || cartItems.isEmpty()) {
            throw new IllegalStateException("EMPTY_CART");
        }

        double total = 0;
        Map<Integer, Integer> quantityByProductId = new HashMap<>();
        for (CartItemDto item : cartItems) {
            Integer productId = item.getProduct().getId();
            // Merge duplicate cart lines for the same product before locking stock.
            quantityByProductId.merge(productId, item.getQuantity(), Integer::sum);
            total += item.getProduct().getPrice() * item.getQuantity();
        }
        long amountCents = Math.round(total * 100);
        if (amountCents < paymentGateway.minimumChargeCents()) {
            throw new IllegalStateException("MIN_CHARGE");
        }

        List<Integer> productIds = new ArrayList<>(quantityByProductId.keySet());
        productIds.sort(Integer::compareTo); // consistent lock order → fewer deadlocks
        List<Product> lockedProducts = productRepository.findAllByIdForUpdate(productIds);
        if (lockedProducts.size() != productIds.size()) {
            throw new InsufficientStockException("One or more products are no longer available");
        }

        Map<Integer, Product> productsById = lockedProducts.stream()
                .collect(Collectors.toMap(Product::getId, product -> product));

        Order order = new Order();
        order.setCreatedDate(new Date());
        order.setUser(user);
        order.setStatus(OrderStatus.PENDING);
        order.setTotalPrice(total);
        order.setOrderItems(new ArrayList<>());

        for (CartItemDto item : cartItems) {
            Product product = productsById.get(item.getProduct().getId());
            int requested = quantityByProductId.get(product.getId());
            if (product.getStock() < requested) {
                throw new InsufficientStockException("Not enough stock for " + product.getName());
            }
        }

        for (Integer productId : productIds) {
            Product product = productsById.get(productId);
            product.setStock(product.getStock() - quantityByProductId.get(productId));
            productRepository.save(product);
        }

        for (CartItemDto item : cartItems) {
            Product product = productsById.get(item.getProduct().getId());
            OrderItem orderItem = new OrderItem(order, product, item.getQuantity(), product.getPrice());
            order.getOrderItems().add(orderItem);
        }

        return orderRepository.save(order);
    }

    /** Maps a persisted order into the provider-neutral shape expected by {@link PaymentGateway}. */
    private CheckoutCommand toCheckoutCommand(Order order) {
        Order withItems = reloadWithItems(order.getId());
        List<CheckoutCommand.LineItem> lineItems = new ArrayList<>();
        for (OrderItem item : withItems.getOrderItems()) {
            Integer productId = item.getProduct() == null ? null : item.getProduct().getId();
            lineItems.add(new CheckoutCommand.LineItem(
                    productId,
                    item.getProductName(),
                    item.getQuantity(),
                    Math.round(item.getPrice() * 100)));
        }
        String email = order.getUser() == null ? null : order.getUser().getEmail();
        Integer userId = order.getUser() == null ? null : order.getUser().getId();
        return new CheckoutCommand(order.getId(), userId, email, lineItems);
    }

    private void attachSessionId(Integer orderId, String sessionId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setSessionId(sessionId);
        orderRepository.save(order);
    }

    private void healSessionId(Integer orderId, String sessionId) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                Order order = orderRepository.findById(orderId).orElseThrow();
                if (order.getSessionId() == null) {
                    order.setSessionId(sessionId);
                    orderRepository.save(order);
                }
            });
        } catch (RuntimeException ignored) {
            // Another request attached it first; the order is still resolvable via metadata.
        }
    }

    /**
     * Locates an order from a retrieved payment session.
     * Tries session id first, then metadata {@code orderId} when attachSessionId never ran.
     */
    private Order resolveOrder(PaymentSession session) {
        if (session.getSessionId() != null) {
            Order bySession = orderRepository.findBySessionIdWithItems(session.getSessionId()).orElse(null);
            if (bySession != null) {
                return bySession;
            }
        }
        if (session.getOrderId() == null) {
            return null;
        }
        return orderRepository.findByIdWithItems(session.getOrderId()).orElse(null);
    }

    /** PENDING → PAID and clear the cart. Idempotent if already terminal. */
    private Order markPaid(Integer orderId) {
        lockOrder(orderId);
        Order order = reloadWithItems(orderId);
        if (order.getStatus() != OrderStatus.PENDING) {
            return order;
        }
        order.setStatus(OrderStatus.PAID);
        orderRepository.save(order);
        cartService.deleteUserCartItems(order.getUser());
        return order;
    }

    /** PENDING → CANCELLED/FAILED and put reserved quantity back on the shelf. */
    private void restoreStock(Integer orderId, OrderStatus terminalStatus) {
        lockOrder(orderId);
        Order order = reloadWithItems(orderId);
        if (order.getStatus() != OrderStatus.PENDING) {
            return;
        }

        List<Integer> productIds = order.getOrderItems().stream()
                .filter(item -> item.getProduct() != null)
                .map(item -> item.getProduct().getId())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        if (!productIds.isEmpty()) {
            List<Product> locked = productRepository.findAllByIdForUpdate(productIds);
            Map<Integer, Product> byId = locked.stream().collect(Collectors.toMap(Product::getId, product -> product));
            for (OrderItem item : order.getOrderItems()) {
                if (item.getProduct() == null) {
                    continue;
                }
                Product product = byId.get(item.getProduct().getId());
                if (product != null) {
                    product.setStock(product.getStock() + item.getQuantity());
                    productRepository.save(product);
                }
            }
        }
        order.setStatus(terminalStatus);
        orderRepository.save(order);
    }

    /**
     * Serializes concurrent transitions of one order, e.g. the Stripe webhook and a reconcile request
     * racing to mark the same order paid. Rows are always locked in the order user, order, product.
     */
    private void lockOrder(Integer orderId) {
        orderRepository.findByIdForUpdate(orderId).orElseThrow();
    }

    private Order reloadWithItems(Integer orderId) {
        return orderRepository.findByIdWithItems(orderId).orElseThrow();
    }

    private OrderResponseDto toDto(Order order) {
        OrderResponseDto dto = new OrderResponseDto();
        dto.setId(order.getId());
        dto.setTotalPrice(order.getTotalPrice());
        dto.setCreatedDate(order.getCreatedDate());
        dto.setSessionId(order.getSessionId());
        dto.setStatus(order.getStatus());
        List<OrderItemResponseDto> items = new ArrayList<>();
        if (order.getOrderItems() != null) {
            for (OrderItem item : order.getOrderItems()) {
                String imageURL = item.getProduct() == null ? null : item.getProduct().getImageURL();
                Integer productId = item.getProduct() == null ? null : item.getProduct().getId();
                items.add(new OrderItemResponseDto(
                        productId,
                        item.getProductName(),
                        imageURL,
                        item.getQuantity(),
                        item.getPrice()));
            }
        }
        dto.setOrderItems(items);
        return dto;
    }
}
