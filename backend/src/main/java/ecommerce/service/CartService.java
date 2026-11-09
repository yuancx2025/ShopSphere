package ecommerce.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ecommerce.common.Money;
import ecommerce.dto.cart.AddToCartDto;
import ecommerce.dto.cart.CartDto;
import ecommerce.dto.cart.CartItemDto;
import ecommerce.exceptions.CustomException;
import ecommerce.exceptions.InsufficientStockException;
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
        int requested = addToCartDto.getQuantity() == null ? 0 : addToCartDto.getQuantity();
        Optional<Cart> existing = cartRepository.findByUserAndProduct(user, product);
        int newQuantity = existing.map(cart -> cart.getQuantity() + requested).orElse(requested);
        assertQuantityAvailable(product, newQuantity);
        if (existing.isPresent()) {
            Cart cart = existing.get();
            cart.setQuantity(newQuantity);
            cartRepository.save(cart);
            return;
        }
        cartRepository.save(new Cart(product, requested, user));
    }

    public CartDto listCartItems(User user) {
        List<Cart> cartList = cartRepository.findAllByUserOrderByCreatedDateDesc(user);
        List<CartItemDto> cartItems = new ArrayList<>();
        long totalCents = 0;
        for (Cart cart : cartList) {
            cartItems.add(new CartItemDto(cart));
            totalCents = Math.addExact(totalCents,
                    Math.multiplyExact(cart.getProduct().getPriceCents(), (long) cart.getQuantity()));
        }
        return new CartDto(cartItems, Money.fromCents(totalCents));
    }

    /** Cart entities for checkout, which needs the products themselves (runs in the caller's transaction). */
    public List<Cart> cartLines(User user) {
        return cartRepository.findAllByUserOrderByCreatedDateDesc(user);
    }

    @Transactional
    public void updateCartItem(Integer cartItemId, Integer quantity, User user) throws CustomException {
        Cart cart = cartRepository.findByIdAndUser(cartItemId, user)
                .orElseThrow(() -> new CustomException("Cart item not found"));
        if (quantity == null || quantity < 1) {
            throw new CustomException("Quantity must be at least 1");
        }
        assertQuantityAvailable(cart.getProduct(), quantity);
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

    private void assertQuantityAvailable(Product product, int quantity) {
        if (quantity > product.getStock()) {
            throw new InsufficientStockException("Not enough stock for " + product.getName());
        }
    }
}
