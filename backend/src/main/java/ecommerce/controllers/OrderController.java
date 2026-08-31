package ecommerce.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;

import ecommerce.common.ApiResponse;
import ecommerce.dto.checkout.CheckoutItemDto;
import ecommerce.dto.checkout.StripeResponse;
import ecommerce.exceptions.AuthenticationFailException;
import ecommerce.exceptions.OrderNotFoundException;
import ecommerce.model.Order;
import ecommerce.model.User;
import ecommerce.security.CurrentUserService;
import ecommerce.service.OrderService;

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
    public ResponseEntity<StripeResponse> checkoutList(@RequestBody List<CheckoutItemDto> checkoutItemDtoList)
            throws StripeException, AuthenticationFailException {
        currentUserService.requireCurrentUser();
        Session session = orderService.createSession(checkoutItemDtoList);
        return new ResponseEntity<>(new StripeResponse(session.getId()), HttpStatus.OK);
    }

    @PostMapping("/add")
    public ResponseEntity<ApiResponse> placeOrder(@RequestParam("sessionId") String sessionId)
            throws AuthenticationFailException {
        User user = currentUserService.requireCurrentUser();
        orderService.placeOrder(user, sessionId);
        return new ResponseEntity<>(new ApiResponse(true, "Order has been placed"), HttpStatus.CREATED);
    }

    @GetMapping("/")
    public ResponseEntity<List<Order>> getAllOrders() throws AuthenticationFailException {
        User user = currentUserService.requireCurrentUser();
        return new ResponseEntity<>(orderService.listOrders(user), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Object> getOrderById(@PathVariable("id") Integer id)
            throws AuthenticationFailException, OrderNotFoundException {
        User user = currentUserService.requireCurrentUser();
        return new ResponseEntity<>(orderService.getOrder(id, user), HttpStatus.OK);
    }
}
