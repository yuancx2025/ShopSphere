package ecommerce.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static ecommerce.catalog.CatalogCacheConfig.CATALOG_PAGE;
import static ecommerce.catalog.CatalogCacheConfig.PRODUCT_DETAIL;

import ecommerce.common.Money;
import ecommerce.events.ProductChange;
import ecommerce.dto.product.ProductDto;
import ecommerce.dto.product.ProductUpdateDto;
import ecommerce.dto.product.StockAdjustmentResponseDto;
import ecommerce.exceptions.BadRequestException;
import ecommerce.exceptions.InsufficientStockException;
import ecommerce.exceptions.ProductNotExistException;
import ecommerce.model.Category;
import ecommerce.model.Product;
import ecommerce.repository.ProductRepository;
import ecommerce.search.SearchIndexJobs;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final SearchIndexJobs searchIndexJobs;

    public ProductService(ProductRepository productRepository, SearchIndexJobs searchIndexJobs) {
        this.productRepository = productRepository;
        this.searchIndexJobs = searchIndexJobs;
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

    /**
     * Creates a product with its initial stock. A new product can appear on any cached catalog page. The search
     * index job commits with the product.
     */
    @Transactional
    @CacheEvict(cacheNames = CATALOG_PAGE, allEntries = true)
    public Product addProduct(ProductDto productDto, Category category) {
        Product product = new Product();
        product.setCategory(category);
        product.setDescription(productDto.getDescription());
        product.setImageURL(productDto.getImageURL());
        product.setPriceCents(toCents(productDto.getPrice()));
        product.setName(productDto.getName());
        product.setStock(productDto.getStock());
        Product saved = productRepository.save(product);
        searchIndexJobs.enqueue(saved.getId(), ProductChange.CREATED);
        return saved;
    }

    /**
     * Updates the descriptive fields of an existing product under a row lock. Stock is never written here
     * (and the column is not updatable through JPA), so an edit cannot undo concurrent reservations.
     * The cached detail and every cached page are evicted after the commit. The search version is bumped and an
     * index job queued in the same transaction, so the change reaches Elasticsearch if and only if it commits.
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = PRODUCT_DETAIL, key = "#productId"),
            @CacheEvict(cacheNames = CATALOG_PAGE, allEntries = true)})
    public void updateProduct(Integer productId, ProductUpdateDto update, Category category)
            throws ProductNotExistException {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new ProductNotExistException("Product id is invalid " + productId));
        product.setName(update.getName());
        product.setImageURL(update.getImageURL());
        product.setDescription(update.getDescription());
        product.setPriceCents(toCents(update.getPrice()));
        product.setCategory(category);
        product.bumpSearchVersion();
        searchIndexJobs.enqueue(productId, ProductChange.UPDATED);
    }

    /**
     * Changes stock by a signed delta in one guarded UPDATE, so it composes with concurrent reservations
     * instead of overwriting them. Rejects a change that would make stock negative. The outbox row commits with
     * the stock change, so the cache invalidator hears about it (stock isn't indexed, so search ignores it).
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = PRODUCT_DETAIL, key = "#productId"),
            @CacheEvict(cacheNames = CATALOG_PAGE, allEntries = true)})
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
        searchIndexJobs.enqueue(productId, ProductChange.STOCK);
        return new StockAdjustmentResponseDto(productId, delta, productRepository.currentStock(productId));
    }

    /**
     * The body of {@code GET /product/{id}}, cached by id when the Redis cache is on. The cached copy includes
     * {@code stock} for display, so it can lag checkout reservations by up to the TTL; admin edits evict it.
     * Checkout never reads it: reservations use a guarded UPDATE on MySQL.
     */
    @Cacheable(cacheNames = PRODUCT_DETAIL, key = "#productId")
    @Transactional(readOnly = true)
    public ProductDto getProductDetail(Integer productId) throws ProductNotExistException {
        return new ProductDto(getProductById(productId));
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
