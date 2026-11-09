package ecommerce.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ecommerce.common.Money;
import ecommerce.dto.product.ProductDto;
import ecommerce.dto.product.ProductUpdateDto;
import ecommerce.dto.product.StockAdjustmentResponseDto;
import ecommerce.exceptions.BadRequestException;
import ecommerce.exceptions.InsufficientStockException;
import ecommerce.exceptions.ProductNotExistException;
import ecommerce.model.Category;
import ecommerce.model.Product;
import ecommerce.repository.ProductRepository;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /** Legacy unbounded list ({@code GET /product/}); new callers use {@code CatalogService}. */
    public List<ProductDto> listProducts() {
        List<Product> products = productRepository.findAll();
        List<ProductDto> productDtos = new ArrayList<>();
        for (Product product : products) {
            productDtos.add(new ProductDto(product));
        }
        return productDtos;
    }

    /** Creates a product with its initial stock. */
    @Transactional
    public Product addProduct(ProductDto productDto, Category category) {
        Product product = new Product();
        product.setCategory(category);
        product.setDescription(productDto.getDescription());
        product.setImageURL(productDto.getImageURL());
        product.setPriceCents(toCents(productDto.getPrice()));
        product.setName(productDto.getName());
        product.setStock(productDto.getStock());
        return productRepository.save(product);
    }

    /**
     * Updates the descriptive fields of an existing product under a row lock. Stock is never written here
     * (and the column is not updatable through JPA), so an edit cannot undo concurrent reservations.
     */
    @Transactional
    public void updateProduct(Integer productId, ProductUpdateDto update, Category category)
            throws ProductNotExistException {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new ProductNotExistException("Product id is invalid " + productId));
        product.setName(update.getName());
        product.setImageURL(update.getImageURL());
        product.setDescription(update.getDescription());
        product.setPriceCents(toCents(update.getPrice()));
        product.setCategory(category);
    }

    /**
     * Changes stock by a signed delta in one guarded UPDATE, so it composes with concurrent reservations
     * instead of overwriting them. Rejects a change that would make stock negative.
     */
    @Transactional
    public StockAdjustmentResponseDto adjustStock(Integer productId, int delta) throws ProductNotExistException {
        if (delta == 0) {
            throw new BadRequestException("delta must not be zero");
        }
        if (productRepository.adjustStock(productId, delta) != 1) {
            if (!productRepository.existsById(productId)) {
                throw new ProductNotExistException("Product id is invalid " + productId);
            }
            throw new InsufficientStockException("Adjustment of " + delta + " would make stock negative");
        }
        return new StockAdjustmentResponseDto(productId, delta, productRepository.currentStock(productId));
    }

    @Transactional(readOnly = true)
    public Product getProductById(Integer productId) throws ProductNotExistException {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotExistException("Product id is invalid " + productId));
    }

    private static long toCents(java.math.BigDecimal price) {
        try {
            return Money.toCents(price);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(ex.getMessage());
        }
    }
}
