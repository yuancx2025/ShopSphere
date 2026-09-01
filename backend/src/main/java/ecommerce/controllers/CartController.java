package ecommerce.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ecommerce.common.ApiResponse;
import ecommerce.dto.cart.AddToCartDto;
import ecommerce.dto.cart.CartDto;
import ecommerce.dto.cart.UpdateCartItemDto;
import ecommerce.exceptions.AuthenticationFailException;
import ecommerce.exceptions.CustomException;
import ecommerce.exceptions.ProductNotExistException;
import ecommerce.model.Product;
import ecommerce.model.User;
import ecommerce.security.CurrentUserService;
import ecommerce.service.CartService;
import ecommerce.service.ProductService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/cart")
public class CartController {

    private final CartService cartService;
    private final ProductService productService;
    private final CurrentUserService currentUserService;

    public CartController(
            CartService cartService,
            ProductService productService,
            CurrentUserService currentUserService) {
        this.cartService = cartService;
        this.productService = productService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/add")
    public ResponseEntity<ApiResponse> addToCart(@Valid @RequestBody AddToCartDto addToCartDto)
            throws ProductNotExistException, AuthenticationFailException {
        User user = currentUserService.requireCurrentUser();
        Product product = productService.getProductById(addToCartDto.getProductId());
        cartService.addToCart(addToCartDto, product, user);
        return new ResponseEntity<>(new ApiResponse(true, "Added to cart"), HttpStatus.CREATED);
    }

    @GetMapping("/")
    public ResponseEntity<CartDto> getCartItems() throws AuthenticationFailException {
        User user = currentUserService.requireCurrentUser();
        return new ResponseEntity<>(cartService.listCartItems(user), HttpStatus.OK);
    }

    @PutMapping("/update/{cartItemId}")
    public ResponseEntity<ApiResponse> updateCartItem(
            @PathVariable("cartItemId") Integer cartItemId,
            @RequestBody @Valid UpdateCartItemDto updateDto)
            throws AuthenticationFailException, CustomException {
        User user = currentUserService.requireCurrentUser();
        cartService.updateCartItem(cartItemId, updateDto.getQuantity(), user);
        return new ResponseEntity<>(new ApiResponse(true, "Cart item updated"), HttpStatus.OK);
    }

    @DeleteMapping("/delete/{cartItemId}")
    public ResponseEntity<ApiResponse> deleteCartItem(@PathVariable("cartItemId") Integer cartItemId)
            throws AuthenticationFailException, CustomException {
        User user = currentUserService.requireCurrentUser();
        cartService.deleteCartItem(cartItemId, user);
        return new ResponseEntity<>(new ApiResponse(true, "Cart item removed"), HttpStatus.OK);
    }
}
