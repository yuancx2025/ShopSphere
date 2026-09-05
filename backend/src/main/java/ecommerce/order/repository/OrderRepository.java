package ecommerce.order.repository;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import ecommerce.model.User;
import ecommerce.order.model.Order;
import ecommerce.order.model.OrderStatus;
import jakarta.persistence.LockModeType;

@Repository
public interface OrderRepository extends JpaRepository<Order, Integer> {

    List<Order> findAllByUserAndStatus(User user, OrderStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Integer id);

    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.orderItems WHERE o.user = :user AND o.status <> :excluded")
    List<Order> findAllVisibleWithItems(@Param("user") User user, @Param("excluded") OrderStatus excluded);

    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.orderItems WHERE o.id = :id")
    Optional<Order> findByIdWithItems(@Param("id") Integer id);

    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.orderItems WHERE o.sessionId = :sessionId")
    Optional<Order> findBySessionIdWithItems(@Param("sessionId") String sessionId);

    /**
     * Orders still holding reserved stock long after their payment session should have settled.
     * Backed by {@code idx_orders_status_created}.
     */
    @Query("SELECT o FROM Order o WHERE o.status = :status AND o.createdDate < :createdBefore ORDER BY o.createdDate ASC")
    List<Order> findStaleByStatus(
            @Param("status") OrderStatus status,
            @Param("createdBefore") Date createdBefore,
            Pageable pageable);
}
