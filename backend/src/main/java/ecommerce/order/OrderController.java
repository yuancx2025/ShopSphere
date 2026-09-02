package ecommerce.order;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ecommerce.exceptions.AuthenticationFailException;
import ecommerce.exceptions.CustomException;
import ecommerce.exceptions.OrderNotFoundException;
import ecommerce.model.User;
import ecommerce.order.dto.OrderResponseDto;
import ecommerce.payment.dto.CheckoutSessionResponseDto;
import ecommerce.security.CurrentUserService;

@RestController
@RequestMapping("/order")
public class OrderController {

    private final OrderService orderService;
    private final CurrentUserService currentUserService;

    public OrderController(OrderService orderService, CurrentUserService currentUserService) {
        this.orderService = orderService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/create-checkout-session")
    public ResponseEntity<CheckoutSessionResponseDto> createCheckoutSession()
            throws AuthenticationFailException, CustomException {
        User user = currentUserService.requireCurrentUser();
        return new ResponseEntity<>(orderService.createSession(user), HttpStatus.OK);
    }

    @GetMapping("/")
    public ResponseEntity<List<OrderResponseDto>> getAllOrders() throws AuthenticationFailException {
        User user = currentUserService.requireCurrentUser();
        return new ResponseEntity<>(orderService.listOrders(user), HttpStatus.OK);
    }

    @GetMapping("/session/{sessionId}")
    public ResponseEntity<OrderResponseDto> getOrderBySession(@PathVariable("sessionId") String sessionId)
            throws AuthenticationFailException, OrderNotFoundException {
        User user = currentUserService.requireCurrentUser();
        return new ResponseEntity<>(orderService.getOrderForSession(sessionId, user), HttpStatus.OK);
    }

    @PostMapping("/session/{sessionId}/reconcile")
    public ResponseEntity<OrderResponseDto> reconcileSession(@PathVariable("sessionId") String sessionId)
            throws AuthenticationFailException, OrderNotFoundException {
        User user = currentUserService.requireCurrentUser();
        return new ResponseEntity<>(orderService.reconcileCheckout(sessionId, user), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponseDto> getOrderById(@PathVariable("id") Integer id)
            throws AuthenticationFailException, OrderNotFoundException {
        User user = currentUserService.requireCurrentUser();
        return new ResponseEntity<>(orderService.getOrder(id, user), HttpStatus.OK);
    }
}
