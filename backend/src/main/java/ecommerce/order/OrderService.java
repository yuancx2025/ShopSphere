package ecommerce.order;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ecommerce.common.Money;
import ecommerce.exceptions.BadRequestException;
import ecommerce.exceptions.CheckoutAttemptClosedException;
import ecommerce.exceptions.CustomException;
import ecommerce.exceptions.InsufficientStockException;
import ecommerce.exceptions.OrderNotFoundException;
import ecommerce.model.Cart;
import ecommerce.model.User;
import ecommerce.order.dto.OrderItemResponseDto;
import ecommerce.order.dto.OrderResponseDto;
import ecommerce.order.model.Order;
import ecommerce.order.model.OrderItem;
import ecommerce.order.model.OrderStatus;
import ecommerce.order.model.ReviewReason;
import ecommerce.order.repository.OrderLockView;
import ecommerce.order.repository.OrderRepository;
import ecommerce.payment.CheckoutCommand;
import ecommerce.payment.PaymentException;
import ecommerce.payment.PaymentGateway;
import ecommerce.payment.PaymentSession;
import ecommerce.payment.dto.CheckoutSessionResponseDto;
import ecommerce.repository.CartRepository;
import ecommerce.repository.ProductRepository;
import ecommerce.repository.UserRepository;
import ecommerce.service.CartService;

/**
 * Order domain service: stock reservation, order lifecycle, and checkout orchestration.
 *
 * <p>Payment provider details live behind {@link PaymentGateway}; this class never imports Stripe.
 * Payment can be applied from two places, both through {@link #applyPaidSession}:
 * <ul>
 *   <li>{@link #fulfillCheckout} — the Stripe webhook (signature-verified, no user ownership check)</li>
 *   <li>{@link #reconcileCheckout} — the success page when the webhook is delayed</li>
 * </ul>
 *
 * <p>Order status flow: PENDING (stock reserved) → PAID | CANCELLED | FAILED | NEEDS_REVIEW.
 *
 * <p>Concurrency rules, enforced by the repositories:
 * <ul>
 *   <li>Stock moves only through guarded SQL ({@code stock = stock - n WHERE stock >= n}), never as an absolute
 *       value from an entity that might predate the row lock.</li>
 *   <li>Order transitions lock the order row with a native locking read, decide from that fresh state, and
 *       apply a conditional UPDATE. Rows are always locked in the order user, order, product.</li>
 *   <li>No provider call happens inside a database transaction.</li>
 * </ul>
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    // A caller must not be able to tell an unknown session from someone else's session.
    private static final String ORDER_NOT_FOUND = "order id is not valid";
    private static final Pattern CHECKOUT_KEY = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private final CartService cartService;
    private final CartRepository cartRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final PaymentGateway paymentGateway;
    private final CheckoutProperties checkoutProperties;
    private final TransactionTemplate transactionTemplate;

    public OrderService(
            CartService cartService,
            CartRepository cartRepository,
            OrderRepository orderRepository,
            ProductRepository productRepository,
            UserRepository userRepository,
            PaymentGateway paymentGateway,
            CheckoutProperties checkoutProperties,
            PlatformTransactionManager transactionManager) {
        this.cartService = cartService;
        this.cartRepository = cartRepository;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.paymentGateway = paymentGateway;
        this.checkoutProperties = checkoutProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // -------------------------------------------------------------------------
    // Checkout start: reserve stock, create a hosted payment session, return URL
    // -------------------------------------------------------------------------

    public CheckoutSessionResponseDto createSession(User user) throws CustomException {
        return createSession(user, null);
    }

    /**
     * Starts or replays a checkout attempt.
     *
     * @param requestedKey the client's {@code Idempotency-Key}. Repeating a key returns the same order and
     *                     provider session instead of reserving stock again. Without one, every call is a new
     *                     attempt.
     */
    public CheckoutSessionResponseDto createSession(User user, String requestedKey) throws CustomException {
        String checkoutKey = normalizeCheckoutKey(requestedKey);
        List<String> sessionsToExpire = new ArrayList<>();
        Order order;
        try {
            order = startAttempt(user, checkoutKey, sessionsToExpire);
        } catch (IllegalStateException ex) {
            if ("EMPTY_CART".equals(ex.getMessage())) {
                throw new CustomException("Cart is empty");
            }
            if ("MIN_CHARGE".equals(ex.getMessage())) {
                throw new CustomException("Order total must be at least $0.50");
            }
            throw ex;
        }
        sessionsToExpire.forEach(paymentGateway::expireSession);

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new CheckoutAttemptClosedException(
                    "This checkout already finished (" + order.getStatus() + "). Start a new checkout.");
        }
        if (order.getSessionId() != null && order.getCheckoutUrl() != null) {
            return new CheckoutSessionResponseDto(order.getSessionId(), order.getCheckoutUrl());
        }
        return openProviderSession(order.getId());
    }

    private Order startAttempt(User user, String checkoutKey, List<String> sessionsToExpire) {
        try {
            return transactionTemplate.execute(status -> reserveOrReplay(user, checkoutKey, sessionsToExpire));
        } catch (DataIntegrityViolationException duplicateKey) {
            // Another request inserted this (user, checkout_key) first; replay its order.
            sessionsToExpire.clear();
            return orderRepository.findByUserAndCheckoutKey(user, checkoutKey).orElseThrow(() -> duplicateKey);
        }
    }

    private Order reserveOrReplay(User user, String checkoutKey, List<String> sessionsToExpire) {
        // Serialize checkout per user so two tabs cannot reserve the same cart twice.
        userRepository.findByIdForUpdate(user.getId());
        Optional<Order> replay = orderRepository.findByUserAndCheckoutKey(user, checkoutKey);
        if (replay.isPresent()) {
            return replay.get();
        }
        // A new attempt replaces the user's open ones: release their stock and expire their sessions.
        for (Order previous : orderRepository.findAllByUserAndStatus(user, OrderStatus.PENDING)) {
            if (previous.getSessionId() != null) {
                sessionsToExpire.add(previous.getSessionId());
            }
            restoreStock(previous.getId(), OrderStatus.CANCELLED);
        }
        return reserveFromCart(user, checkoutKey);
    }

    private CheckoutSessionResponseDto openProviderSession(Integer orderId) throws CustomException {
        CheckoutCommand command = toCheckoutCommand(reloadWithItems(orderId));
        PaymentSession session;
        try {
            session = paymentGateway.createSession(command);
        } catch (PaymentException ex) {
            // Keep it simple: any provider failure cancels the attempt and returns the stock. If the provider did
            // create a session anyway, a payment on it lands in NEEDS_REVIEW (PAID_AFTER_RELEASE), never lost.
            log.warn("Could not create a checkout session for order {}", orderId, ex);
            releaseCheckoutByOrderId(orderId, OrderStatus.CANCELLED);
            throw new CustomException("Unable to start payment");
        }
        attachSession(orderId, session);
        return new CheckoutSessionResponseDto(session.getSessionId(), session.getUrl());
    }

    private void attachSession(Integer orderId, PaymentSession session) {
        try {
            transactionTemplate.executeWithoutResult(status ->
                    orderRepository.attachSession(orderId, session.getSessionId(), session.getUrl()));
        } catch (RuntimeException ex) {
            // The provider session exists. Fulfillment still finds the order through metadata.orderId.
            log.warn("Could not attach session {} to order {}", session.getSessionId(), orderId, ex);
        }
    }

    // -------------------------------------------------------------------------
    // Fulfillment & release (webhook, success page, reconciler)
    // -------------------------------------------------------------------------

    /**
     * Applies a payment the provider reports. Webhook-only path: the caller is signature-verified, so there is
     * no user ownership check.
     */
    public OrderResponseDto fulfillCheckout(String sessionId) {
        PaymentSession session = paymentGateway.retrieveSession(sessionId);
        Integer orderId = resolveOrderId(session);
        if (orderId == null) {
            log.warn("Provider session {} (paid={}) matches no order", sessionId, session.isPaid());
            return null;
        }
        if (session.isPaid()) {
            transactionTemplate.executeWithoutResult(status -> applyPaidSession(orderId, session));
        }
        return toDto(reloadWithItems(orderId));
    }

    /** Restores reserved stock when checkout expires or async payment fails. */
    public void releaseCheckout(String sessionId, OrderStatus terminalStatus) {
        Integer orderId = orderRepository.findIdBySessionId(sessionId).orElse(null);
        if (orderId == null) {
            // The session was never attached; the provider's metadata is the only link back to the order.
            PaymentSession session = paymentGateway.retrieveSession(sessionId);
            Order order = session.getOrderId() == null ? null
                    : orderRepository.findById(session.getOrderId()).orElse(null);
            if (order == null || (order.getSessionId() != null && !order.getSessionId().equals(sessionId))) {
                return;
            }
            orderId = order.getId();
        }
        releaseCheckoutByOrderId(orderId, terminalStatus);
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

    /** Read-only lookup for the success page poll loop. Never calls the provider or mutates state. */
    public OrderResponseDto getOrderForSession(String sessionId, User user) throws OrderNotFoundException {
        Order order = orderRepository.findBySessionIdWithItems(sessionId)
                .orElseThrow(() -> new OrderNotFoundException(ORDER_NOT_FOUND));
        requireOwner(order, user);
        return toDto(order);
    }

    /**
     * User-triggered recovery when the webhook is delayed or missing (e.g. local dev without
     * {@code stripe listen}). Ownership is checked before any write or provider call that could change the order.
     */
    public OrderResponseDto reconcileCheckout(String sessionId, User user) throws OrderNotFoundException {
        Integer orderId = orderRepository.findIdBySessionId(sessionId).orElse(null);
        PaymentSession session = null;
        if (orderId == null) {
            // The session id was never persisted, so provider metadata is the only link back to the order.
            session = paymentGateway.retrieveSession(sessionId);
            if (session.getOrderId() == null) {
                throw new OrderNotFoundException(ORDER_NOT_FOUND);
            }
            orderId = session.getOrderId();
        }
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new OrderNotFoundException(ORDER_NOT_FOUND));
        requireOwner(order, user);

        if (order.getStatus() == OrderStatus.PAID || order.getStatus() == OrderStatus.NEEDS_REVIEW) {
            return toDto(order);
        }
        if (session == null) {
            session = paymentGateway.retrieveSession(sessionId);
        }
        if (session.isPaid()) {
            Integer id = orderId;
            PaymentSession paid = session;
            transactionTemplate.executeWithoutResult(status -> applyPaidSession(id, paid));
        } else if (order.getSessionId() == null && order.getStatus() == OrderStatus.PENDING) {
            attachSession(orderId, session);
        }
        return toDto(reloadWithItems(orderId));
    }

    /** Same error text for missing and foreign orders so callers cannot probe session ids. */
    private void requireOwner(Order order, User user) throws OrderNotFoundException {
        if (order.getUser() == null || !order.getUser().getId().equals(user.getId())) {
            throw new OrderNotFoundException(ORDER_NOT_FOUND);
        }
    }

    // -------------------------------------------------------------------------
    // Stock reservation & state transitions (each runs inside a transaction)
    // -------------------------------------------------------------------------

    /**
     * Converts the user's cart into a PENDING order and takes the units out of stock. Each product's stock is
     * decremented with one guarded UPDATE, in product id order so multi-item carts cannot deadlock. If any
     * product is short, the exception rolls back the whole reservation.
     * Cart is not cleared here; that happens when the payment is applied.
     */
    private Order reserveFromCart(User user, String checkoutKey) {
        List<Cart> lines = cartService.cartLines(user);
        if (lines.isEmpty()) {
            throw new IllegalStateException("EMPTY_CART");
        }

        Map<Integer, Integer> quantityByProductId = new TreeMap<>();
        Map<Integer, String> nameByProductId = new TreeMap<>();
        long totalCents = 0;
        for (Cart line : lines) {
            Integer productId = line.getProduct().getId();
            quantityByProductId.merge(productId, line.getQuantity(), Integer::sum);
            nameByProductId.put(productId, line.getProduct().getName());
            totalCents = Math.addExact(totalCents,
                    Math.multiplyExact(line.getProduct().getPriceCents(), (long) line.getQuantity()));
        }
        if (totalCents < paymentGateway.minimumChargeCents()) {
            throw new IllegalStateException("MIN_CHARGE");
        }

        for (Map.Entry<Integer, Integer> entry : quantityByProductId.entrySet()) {
            if (productRepository.reserveStock(entry.getKey(), entry.getValue()) != 1) {
                throw new InsufficientStockException("Not enough stock for " + nameByProductId.get(entry.getKey()));
            }
        }

        Order order = new Order();
        order.setCreatedDate(new Date());
        order.setUser(user);
        order.setStatus(OrderStatus.PENDING);
        order.setTotalCents(totalCents);
        order.setCurrency(Money.DEFAULT_CURRENCY);
        order.setCheckoutKey(checkoutKey);
        order.setStockHeld(true);
        order.setOrderItems(new ArrayList<>());
        for (Cart line : lines) {
            order.getOrderItems().add(
                    new OrderItem(order, line.getProduct(), line.getQuantity(), line.getProduct().getPriceCents()));
        }
        return orderRepository.save(order);
    }

    /**
     * Applies a paid provider session to its order: PENDING → PAID when the session matches the order, and
     * NEEDS_REVIEW when it does not or when the reservation was already released. Idempotent.
     */
    private void applyPaidSession(Integer orderId, PaymentSession session) {
        OrderLockView order = orderRepository.lockForTransition(orderId).orElseThrow();
        OrderStatus status = OrderStatus.valueOf(order.getStatus());
        if (status == OrderStatus.PAID || status == OrderStatus.NEEDS_REVIEW) {
            return; // duplicate delivery, or already flagged
        }
        if (status == OrderStatus.CANCELLED || status == OrderStatus.FAILED) {
            // The customer was charged after the units went back on the shelf. Never ignore it.
            orderRepository.markNeedsReview(orderId, ReviewReason.PAID_AFTER_RELEASE.name(), session.getSessionId());
            log.warn("Order {} was paid by session {} after its stock was released; needs refund or review",
                    orderId, session.getSessionId());
            return;
        }
        ReviewReason mismatch = mismatch(order, session);
        if (mismatch != null) {
            orderRepository.markNeedsReview(orderId, mismatch.name(), session.getSessionId());
            log.warn("Paid session {} does not match order {}: {}", session.getSessionId(), orderId, mismatch);
            return;
        }
        orderRepository.markPaid(orderId, session.getSessionId());
        cartRepository.deleteAllByUserId(order.getUserId());
    }

    /** The paid session must be for this order, this session, this amount and this currency. */
    static ReviewReason mismatch(OrderLockView order, PaymentSession session) {
        if (session.getOrderId() != null && !session.getOrderId().equals(order.getId())) {
            return ReviewReason.ORDER_MISMATCH;
        }
        if (order.getSessionId() != null && !order.getSessionId().equals(session.getSessionId())) {
            return ReviewReason.SESSION_MISMATCH;
        }
        if (order.getTotalCents() == null || !order.getTotalCents().equals(session.getAmountTotalCents())) {
            return ReviewReason.AMOUNT_MISMATCH;
        }
        if (session.getCurrency() == null || !session.getCurrency().equalsIgnoreCase(order.getCurrency())) {
            return ReviewReason.CURRENCY_MISMATCH;
        }
        return null;
    }

    /** PENDING → CANCELLED/FAILED and put the reserved units back, exactly once. */
    private void restoreStock(Integer orderId, OrderStatus terminalStatus) {
        OrderLockView order = orderRepository.lockForTransition(orderId).orElseThrow();
        if (!OrderStatus.PENDING.name().equals(order.getStatus())) {
            return;
        }
        if (orderRepository.releaseReservation(orderId, terminalStatus.name()) != 1) {
            return;
        }
        Map<Integer, Integer> quantityByProductId = new TreeMap<>();
        for (Object[] row : orderRepository.findItemQuantities(orderId)) {
            quantityByProductId.merge((Integer) row[0], (Integer) row[1], Integer::sum);
        }
        quantityByProductId.forEach(productRepository::releaseStock);
    }

    // -------------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------------

    /**
     * Maps a persisted order into the provider-neutral command. Every field comes from the stored order, so a
     * retry of the same attempt sends identical parameters under the same idempotency key.
     */
    private CheckoutCommand toCheckoutCommand(Order order) {
        List<CheckoutCommand.LineItem> lineItems = new ArrayList<>();
        for (OrderItem item : order.getOrderItems()) {
            Integer productId = item.getProduct() == null ? null : item.getProduct().getId();
            lineItems.add(new CheckoutCommand.LineItem(productId, item.getProductName(), item.getQuantity(),
                    item.getUnitPriceCents()));
        }
        String email = order.getUser() == null ? null : order.getUser().getEmail();
        Integer userId = order.getUser() == null ? null : order.getUser().getId();
        Instant expiresAt = order.getCreatedDate().toInstant()
                .plus(checkoutProperties.getSessionTtl())
                .truncatedTo(ChronoUnit.SECONDS);
        return new CheckoutCommand(order.getId(), userId, email, lineItems, order.getTotalCents(),
                order.getCurrency(), providerIdempotencyKey(order), expiresAt);
    }

    /** Unique per order (order id) and per attempt (checkout key), stable across retries. */
    static String providerIdempotencyKey(Order order) {
        return "shopsphere-checkout-" + order.getId() + "-" + order.getCheckoutKey();
    }

    private static String normalizeCheckoutKey(String requestedKey) {
        if (requestedKey == null || requestedKey.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String key = requestedKey.trim();
        if (!CHECKOUT_KEY.matcher(key).matches()) {
            throw new BadRequestException("Idempotency-Key must be 8-64 characters of letters, digits, '-' or '_'");
        }
        return key;
    }

    /**
     * Resolves the order a provider session belongs to: by attached session id first, then by the order id
     * the session carries in its metadata.
     */
    private Integer resolveOrderId(PaymentSession session) {
        if (session.getSessionId() != null) {
            Optional<Integer> bySession = orderRepository.findIdBySessionId(session.getSessionId());
            if (bySession.isPresent()) {
                return bySession.get();
            }
        }
        if (session.getOrderId() != null && orderRepository.existsById(session.getOrderId())) {
            return session.getOrderId();
        }
        return null;
    }

    private Order reloadWithItems(Integer orderId) {
        return orderRepository.findByIdWithItems(orderId).orElseThrow();
    }

    private OrderResponseDto toDto(Order order) {
        OrderResponseDto dto = new OrderResponseDto();
        dto.setId(order.getId());
        dto.setTotalPrice(order.getTotalCents() == null ? null : Money.fromCents(order.getTotalCents()));
        dto.setCurrency(order.getCurrency());
        dto.setCreatedDate(order.getCreatedDate());
        dto.setSessionId(order.getSessionId());
        dto.setStatus(order.getStatus());
        dto.setReviewReason(order.getReviewReason());
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
                        Money.fromCents(item.getUnitPriceCents())));
            }
        }
        dto.setOrderItems(items);
        return dto;
    }
}
