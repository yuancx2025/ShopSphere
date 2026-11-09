package ecommerce.controllers;


import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ecommerce.catalog.CatalogService;
import ecommerce.common.ApiResponse;
import ecommerce.dto.product.ProductDto;
import ecommerce.dto.product.ProductPageDto;
import ecommerce.dto.product.ProductUpdateDto;
import ecommerce.dto.product.StockAdjustmentDto;
import ecommerce.dto.product.StockAdjustmentResponseDto;
import ecommerce.exceptions.ProductNotExistException;
import ecommerce.model.Category;
import ecommerce.service.CategoryService;
import ecommerce.service.ProductService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/product")
public class ProductController {

    private final ProductService productService;
    private final CategoryService categoryService;
    private final CatalogService catalogService;

    public ProductController(ProductService productService, CategoryService categoryService,
            CatalogService catalogService) {
        this.productService = productService;
        this.categoryService = categoryService;
        this.catalogService = catalogService;
    }

    /** Legacy: the whole catalog in one response. Kept during the migration; new callers use /product/page. */
    @GetMapping("/")
    public ResponseEntity<List<ProductDto>> getProducts() {
        List<ProductDto> body = productService.listProducts();
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    /**
     * Bounded, keyset-paginated catalog. {@code size} defaults to 20 and is capped at 100; {@code sort} is
     * {@code id} (default) or {@code price_asc} (needs {@code categoryId}).
     * Pass the response's {@code nextCursor} back as {@code cursor} for the next page.
     */
    @GetMapping("/page")
    public ResponseEntity<ProductPageDto> getProductPage(
            @RequestParam(value = "categoryId", required = false) Integer categoryId,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "cursor", required = false) String cursor) {
        return ResponseEntity.ok(catalogService.page(categoryId, sort, size, cursor));
    }

    @GetMapping("/{productID}")
    public ResponseEntity<ProductDto> getProduct(@PathVariable("productID") Integer productID)
            throws ProductNotExistException {
        return new ResponseEntity<>(productService.getProductDetail(productID), HttpStatus.OK);
    }

    @PostMapping("/add")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse> addProduct(@RequestBody @Valid ProductDto productDto) {
        Optional<Category> optionalCategory = categoryService.readCategory(productDto.getCategoryId());
        if (!optionalCategory.isPresent()) {
            return new ResponseEntity<>(new ApiResponse(false, "category is invalid"), HttpStatus.CONFLICT);
        }
        Category category = optionalCategory.get();
        productService.addProduct(productDto, category);
        return new ResponseEntity<>(new ApiResponse(true, "Product has been added"), HttpStatus.CREATED);
    }

    @PostMapping("/update/{productID}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse> updateProduct(@PathVariable("productID") Integer productID,
            @RequestBody @Valid ProductUpdateDto productDto) throws ProductNotExistException {
        Optional<Category> optionalCategory = categoryService.readCategory(productDto.getCategoryId());
        if (!optionalCategory.isPresent()) {
            return new ResponseEntity<ApiResponse>(new ApiResponse(false, "category is invalid"), HttpStatus.CONFLICT);
        }
        Category category = optionalCategory.get();
        productService.updateProduct(productID, productDto, category);
        return new ResponseEntity<>(new ApiResponse(true, "Product has been updated"), HttpStatus.OK);
    }

    /** Changes stock by a signed delta (409 if it would go negative). The only way to edit stock. */
    @PostMapping("/{productID}/stock-adjustments")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<StockAdjustmentResponseDto> adjustStock(@PathVariable("productID") Integer productID,
            @RequestBody @Valid StockAdjustmentDto adjustment) throws ProductNotExistException {
        return ResponseEntity.ok(productService.adjustStock(productID, adjustment.getDelta()));
    }
}
