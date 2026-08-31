package ecommerce.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ecommerce.dto.cart.AddToCartDto;
import ecommerce.dto.cart.CartDto;
import ecommerce.dto.cart.CartItemDto;
import ecommerce.exceptions.CustomException;
import ecommerce.model.Cart;
import ecommerce.model.Product;
import ecommerce.model.User;
import ecommerce.repository.CartRepository;

@Service
public class CartService {

    private final CartRepository cartRepository;

    public CartService(CartRepository cartRepository) {
        this.cartRepository = cartRepository;
    }

    @Transactional
    public void addToCart(AddToCartDto addToCartDto, Product product, User user) {
        Optional<Cart> existing = cartRepository.findByUserAndProduct(user, product);
        if (existing.isPresent()) {
            Cart cart = existing.get();
            cart.setQuantity(cart.getQuantity() + addToCartDto.getQuantity());
            cartRepository.save(cart);
            return;
        }
        cartRepository.save(new Cart(product, addToCartDto.getQuantity(), user));
    }

    public CartDto listCartItems(User user) {
        List<Cart> cartList = cartRepository.findAllByUserOrderByCreatedDateDesc(user);
        List<CartItemDto> cartItems = new ArrayList<>();
        for (Cart cart : cartList) {
            cartItems.add(new CartItemDto(cart));
        }

        double totalCost = 0;
        for (CartItemDto cartItemDto : cartItems) {
            totalCost += cartItemDto.getProduct().getPrice() * cartItemDto.getQuantity();
        }
        return new CartDto(cartItems, totalCost);
    }

    @Transactional
    public void updateCartItem(Integer cartItemId, Integer quantity, User user) throws CustomException {
        Cart cart = cartRepository.findByIdAndUser(cartItemId, user)
                .orElseThrow(() -> new CustomException("Cart item not found"));
        if (quantity == null || quantity < 1) {
            throw new CustomException("Quantity must be at least 1");
        }
        cart.setQuantity(quantity);
        cartRepository.save(cart);
    }

    @Transactional
    public void deleteCartItem(Integer cartItemId, User user) throws CustomException {
        Cart cart = cartRepository.findByIdAndUser(cartItemId, user)
                .orElseThrow(() -> new CustomException("Cart item not found"));
        cartRepository.delete(cart);
    }

    @Transactional
    public void deleteUserCartItems(User user) {
        cartRepository.deleteByUser(user);
    }
}
