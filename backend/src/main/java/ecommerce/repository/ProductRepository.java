package ecommerce.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import ecommerce.model.Product;
import jakarta.persistence.LockModeType;

/**
 * Stock is changed only by the three guarded statements below, never by saving a {@link Product} with an
 * absolute value (the column is not updatable through JPA).
 *
 * <p>Why: a {@code SELECT ... FOR UPDATE} through JPA returns the entity instance already in the persistence
 * context when there is one, without refreshing it. Checkout loads the cart (and its products) first, so the
 * locked product could carry the stock value from before the lock, and saving {@code stock - qty} wrote an
 * outdated number back. The 100-shopper test in {@code CheckoutBurstTest} oversold a 20-unit SKU 100 times
 * this way. Each statement here reads and writes the current row under its own row lock in one step.
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, Integer> {

    /** Takes {@code quantity} units if that many are available. Returns 1 on success, 0 if not enough stock. */
    @Modifying
    @Query(value = "UPDATE products SET stock = stock - :quantity WHERE id = :id AND stock >= :quantity",
            nativeQuery = true)
    int reserveStock(@Param("id") Integer id, @Param("quantity") int quantity);

    /** Puts released units back on the shelf. */
    @Modifying
    @Query(value = "UPDATE products SET stock = stock + :quantity WHERE id = :id", nativeQuery = true)
    int releaseStock(@Param("id") Integer id, @Param("quantity") int quantity);

    /** Admin adjustment by a signed delta. Returns 0 if the product is missing or the result would be negative. */
    @Modifying
    @Query(value = "UPDATE products SET stock = stock + :delta WHERE id = :id AND stock + :delta >= 0",
            nativeQuery = true)
    int adjustStock(@Param("id") Integer id, @Param("delta") int delta);

    /** Current stock as this transaction sees it (including its own adjustment). */
    @Query(value = "SELECT stock FROM products WHERE id = :id", nativeQuery = true)
    Integer currentStock(@Param("id") Integer id);

    /** Locks the row for an admin edit of the descriptive fields. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Integer id);
}
