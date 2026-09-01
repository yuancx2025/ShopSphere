package ecommerce.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;

import ecommerce.dto.cart.CartDto;
import ecommerce.dto.cart.CartItemDto;
import ecommerce.dto.checkout.StripeResponseDto;
import ecommerce.dto.order.OrderItemResponseDto;
import ecommerce.dto.order.OrderResponseDto;
import ecommerce.exceptions.CustomException;
import ecommerce.exceptions.InsufficientStockException;
import ecommerce.exceptions.OrderNotFoundException;
import ecommerce.model.Order;
import ecommerce.model.OrderItem;
import ecommerce.model.OrderStatus;
import ecommerce.model.ProcessedStripeEvent;
import ecommerce.model.Product;
import ecommerce.model.User;
import ecommerce.repository.OrderRepository;
import ecommerce.repository.ProcessedStripeEventRepository;
import ecommerce.repository.ProductRepository;
import ecommerce.repository.UserRepository;
import jakarta.annotation.PostConstruct;

@Service
public class OrderService {

    private static final long SESSION_TTL_SECONDS = 30 * 60;
    private static final long STRIPE_MIN_AMOUNT_CENTS = 50;

    private final CartService cartService;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ProcessedStripeEventRepository processedStripeEventRepository;
    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;
    private final String baseURL;
    private final String apiKey;

    public OrderService(
            CartService cartService,
            OrderRepository orderRepository,
            ProductRepository productRepository,
            ProcessedStripeEventRepository processedStripeEventRepository,
            UserRepository userRepository,
            PlatformTransactionManager transactionManager,
            @Value("${BASE_URL}") String baseURL,
            @Value("${STRIPE_SECRET_KEY}") String apiKey) {
        this.cartService = cartService;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.processedStripeEventRepository = processedStripeEventRepository;
        this.userRepository = userRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.baseURL = baseURL;
        this.apiKey = apiKey;
    }

    @PostConstruct
    void initStripe() {
        Stripe.apiKey = apiKey;
    }

    public StripeResponseDto createSession(User user) throws StripeException, CustomException {
        List<String> sessionsToExpire = new ArrayList<>();
        Order order;
        try {
            order = transactionTemplate.execute(status -> {
                userRepository.findByIdForUpdate(user.getId());
                for (Order previous : orderRepository.findAllByUserAndStatus(user, OrderStatus.PENDING)) {
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
        sessionsToExpire.forEach(this::expireStripeSession);

        try {
            Session session = createStripeSession(order);
            try {
                transactionTemplate.executeWithoutResult(status -> attachSessionId(order.getId(), session.getId()));
            } catch (RuntimeException ignored) {
                // Stripe already has the session; webhook can fulfill via metadata.orderId.
            }
            return new StripeResponseDto(session.getId(), session.getUrl());
        } catch (StripeException ex) {
            releaseCheckoutByOrderId(order.getId(), OrderStatus.CANCELLED);
            throw ex;
        }
    }

    public OrderResponseDto fulfillCheckout(String sessionId) throws StripeException {
        Session session = Session.retrieve(sessionId);
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
        if (!"paid".equalsIgnoreCase(session.getPaymentStatus())) {
            return toDto(reloadWithItems(order.getId()));
        }

        Order paid = transactionTemplate.execute(status -> markPaid(order.getId()));
        return toDto(paid);
    }

    public void releaseCheckout(String sessionId, OrderStatus terminalStatus) {
        orderRepository.findBySessionIdWithItems(sessionId)
                .ifPresent(order -> releaseCheckoutByOrderId(order.getId(), terminalStatus));
    }

    public void releaseCheckoutByOrderId(Integer orderId, OrderStatus terminalStatus) {
        transactionTemplate.executeWithoutResult(status -> restoreStock(orderId, terminalStatus));
    }

    public void recordProcessedEvent(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        if (processedStripeEventRepository.existsById(eventId)) {
            return;
        }
        try {
            processedStripeEventRepository.save(new ProcessedStripeEvent(eventId, Instant.now()));
        } catch (DataIntegrityViolationException ignored) {
            // Another request already recorded this Stripe event.
        }
    }

    public boolean alreadyProcessed(String eventId) {
        return eventId != null && processedStripeEventRepository.existsById(eventId);
    }

    public List<OrderResponseDto> listOrders(User user) {
        List<Order> orders = orderRepository.findAllVisibleWithItems(user, OrderStatus.PENDING);
        orders.sort(Comparator.comparing(Order::getCreatedDate, Comparator.nullsLast(Comparator.reverseOrder())));
        return orders.stream().map(this::toDto).collect(Collectors.toList());
    }

    public OrderResponseDto getOrder(Integer orderId, User user) throws OrderNotFoundException {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new OrderNotFoundException("order id is not valid"));
        if (order.getUser() == null || !order.getUser().getId().equals(user.getId())) {
            throw new OrderNotFoundException("order does not belong to user");
        }
        return toDto(order);
    }

    public OrderResponseDto getOrderForSession(String sessionId, User user)
            throws OrderNotFoundException, StripeException {
        OrderResponseDto dto = fulfillCheckout(sessionId);
        if (dto == null) {
            throw new OrderNotFoundException("order id is not valid");
        }
        Order order = orderRepository.findBySessionIdWithItems(sessionId)
                .or(() -> orderRepository.findByIdWithItems(dto.getId()))
                .orElseThrow(() -> new OrderNotFoundException("order id is not valid"));
        if (order.getUser() == null || !order.getUser().getId().equals(user.getId())) {
            throw new OrderNotFoundException("order does not belong to user");
        }
        return toDto(order);
    }

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
            quantityByProductId.merge(productId, item.getQuantity(), Integer::sum);
            total += item.getProduct().getPrice() * item.getQuantity();
        }
        long amountCents = Math.round(total * 100);
        if (amountCents < STRIPE_MIN_AMOUNT_CENTS) {
            throw new IllegalStateException("MIN_CHARGE");
        }

        List<Integer> productIds = new ArrayList<>(quantityByProductId.keySet());
        productIds.sort(Integer::compareTo);
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

    private Session createStripeSession(Order order) throws StripeException {
        String successURL = trimBaseUrl(baseURL) + "/payment/success?session_id={CHECKOUT_SESSION_ID}";
        String failedURL = trimBaseUrl(baseURL) + "/payment/failed";

        Order withItems = reloadWithItems(order.getId());
        List<SessionCreateParams.LineItem> lineItems = new ArrayList<>();
        for (OrderItem item : withItems.getOrderItems()) {
            lineItems.add(SessionCreateParams.LineItem.builder()
                    .setQuantity((long) item.getQuantity())
                    .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                            .setCurrency("usd")
                            .setUnitAmount(Math.round(item.getPrice() * 100))
                            .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                    .setName(item.getProductName())
                                    .putMetadata(
                                            "productId",
                                            item.getProduct() == null ? "" : String.valueOf(item.getProduct().getId()))
                                    .build())
                            .build())
                    .build());
        }

        SessionCreateParams.Builder params = SessionCreateParams.builder()
                .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setCancelUrl(failedURL)
                .setSuccessUrl(successURL)
                .addAllLineItem(lineItems)
                .setClientReferenceId(String.valueOf(order.getUser().getId()))
                .putMetadata("orderId", String.valueOf(order.getId()))
                .putMetadata("userId", String.valueOf(order.getUser().getId()))
                .setExpiresAt(Instant.now().plusSeconds(SESSION_TTL_SECONDS).getEpochSecond());

        if (order.getUser().getEmail() != null && !order.getUser().getEmail().isBlank()) {
            params.setCustomerEmail(order.getUser().getEmail());
        }

        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey("checkout-order-" + order.getId())
                .build();
        return Session.create(params.build(), options);
    }

    private void attachSessionId(Integer orderId, String sessionId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setSessionId(sessionId);
        orderRepository.save(order);
    }

    private Order resolveOrder(Session session) {
        if (session.getId() != null) {
            Order bySession = orderRepository.findBySessionIdWithItems(session.getId()).orElse(null);
            if (bySession != null) {
                return bySession;
            }
        }
        String orderId = session.getMetadata() == null ? null : session.getMetadata().get("orderId");
        if (orderId == null || orderId.isBlank()) {
            return null;
        }
        try {
            return orderRepository.findByIdWithItems(Integer.valueOf(orderId)).orElse(null);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Order markPaid(Integer orderId) {
        Order order = reloadWithItems(orderId);
        if (order.getStatus() == OrderStatus.PAID) {
            return order;
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            return order;
        }
        order.setStatus(OrderStatus.PAID);
        orderRepository.save(order);
        cartService.deleteUserCartItems(order.getUser());
        return order;
    }

    private void restoreStock(Integer orderId, OrderStatus terminalStatus) {
        Order order = reloadWithItems(orderId);
        if (order.getStatus() == OrderStatus.PAID) {
            return;
        }
        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.FAILED) {
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

    private void expireStripeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            Session session = Session.retrieve(sessionId);
            session.expire();
        } catch (StripeException | RuntimeException ignored) {
            // Already expired, already paid, or SDK without expire().
        }
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

    private String trimBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "http://localhost:3000";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
