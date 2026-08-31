package ecommerce.controllers;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ecommerce.common.ApiResponse;
import ecommerce.dto.product.ProductDto;
import ecommerce.exceptions.AuthenticationFailException;
import ecommerce.exceptions.ProductNotExistException;
import ecommerce.model.Product;
import ecommerce.model.User;
import ecommerce.model.WishList;
import ecommerce.security.CurrentUserService;
import ecommerce.service.ProductService;
import ecommerce.service.WishListService;

@RestController
@RequestMapping("/wishlist")
public class WishListController {

    private final WishListService wishListService;
    private final CurrentUserService currentUserService;
    private final ProductService productService;

    public WishListController(
            WishListService wishListService,
            CurrentUserService currentUserService,
            ProductService productService) {
        this.wishListService = wishListService;
        this.currentUserService = currentUserService;
        this.productService = productService;
    }

    @PostMapping("/add")
    public ResponseEntity<ApiResponse> addWishList(@RequestBody ProductDto productDto)
            throws AuthenticationFailException, ProductNotExistException {
        User user = currentUserService.requireCurrentUser();
        Product product = productService.getProductById(productDto.getId());
        wishListService.createWishlist(new WishList(user, product));
        return new ResponseEntity<>(new ApiResponse(true, "Added to wishlist"), HttpStatus.CREATED);
    }

    @GetMapping("/")
    public ResponseEntity<List<ProductDto>> getWishList() throws AuthenticationFailException {
        User user = currentUserService.requireCurrentUser();
        List<ProductDto> products = new ArrayList<>();
        for (WishList wishList : wishListService.readWishList(user)) {
            products.add(new ProductDto(wishList.getProduct()));
        }
        return new ResponseEntity<>(products, HttpStatus.OK);
    }
}
