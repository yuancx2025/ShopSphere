package ecommerce.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private @NotNull String name;

    @Column(name = "image_url")
    private @NotNull String imageURL;

    /** Source of truth for the price, in cents. */
    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    /**
     * Legacy DOUBLE column kept by Flyway V4 so the previous release can still read prices after a rollback.
     * Always derived from {@link #priceCents}; never read for money math.
     */
    @Column(name = "price", nullable = false)
    private double legacyPrice;

    private @NotNull String description;

    /**
     * Not updatable through JPA: stock only changes through the guarded SQL in {@code ProductRepository}
     * ({@code reserveStock}, {@code releaseStock}, {@code adjustStock}). An entity loaded before a row lock can
     * hold a stale value, and writing it back as an absolute number would undo concurrent reservations.
     */
    @Column(name = "stock", nullable = false, updatable = false)
    private int stock;

    /** Bumped by every admin write to a searchable field; the Elasticsearch indexer's external version. */
    @JsonIgnore
    @Column(name = "search_version", nullable = false)
    private long searchVersion = 1;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    Category category;


    public Product() {
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getImageURL() {
        return imageURL;
    }

    public void setImageURL(String imageURL) {
        this.imageURL = imageURL;
    }

    public long getPriceCents() {
        return priceCents;
    }

    public void setPriceCents(long priceCents) {
        if (priceCents < 0) {
            throw new IllegalArgumentException("price must not be negative");
        }
        this.priceCents = priceCents;
        this.legacyPrice = priceCents / 100.0;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public int getStock() {
        return stock;
    }

    /** Initial stock for a new product. Has no effect on a persisted product (the column is not updatable). */
    public void setStock(int stock) {
        this.stock = stock;
    }

    public long getSearchVersion() {
        return searchVersion;
    }

    /** Marks the searchable fields as changed, so the next index job wins over any older snapshot. */
    public void bumpSearchVersion() {
        searchVersion++;
    }

    @Override
    public String toString() {
        return "Product{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", imageURL='" + imageURL + '\'' +
                ", priceCents=" + priceCents +
                ", description='" + description + '\'' +
                '}';
    }
}
