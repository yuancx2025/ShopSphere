package ecommerce.order.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import ecommerce.model.User;
import ecommerce.order.model.Order;
import ecommerce.order.model.OrderStatus;

/**
 * Order state transitions are guarded SQL, not entity saves. Each one runs after {@link #lockForTransition}
 * and also re-checks the expected status in its WHERE clause, so a transition can only move an order out of the
 * state it is actually in. The persistence context is cleared afterwards so no stale entity is flushed later.
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, Integer> {

    List<Order> findAllByUserAndStatus(User user, OrderStatus status);

    Optional<Order> findByUserAndCheckoutKey(User user, String checkoutKey);

    @Query("SELECT o.id FROM Order o WHERE o.sessionId = :sessionId")
    Optional<Integer> findIdBySessionId(@Param("sessionId") String sessionId);

    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.orderItems WHERE o.user = :user AND o.status <> :excluded")
    List<Order> findAllVisibleWithItems(@Param("user") User user, @Param("excluded") OrderStatus excluded);

    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.orderItems WHERE o.id = :id")
    Optional<Order> findByIdWithItems(@Param("id") Integer id);

    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.orderItems WHERE o.sessionId = :sessionId")
    Optional<Order> findBySessionIdWithItems(@Param("sessionId") String sessionId);

    /** (productId, quantity) pairs of an order; item quantities never change after reservation. */
    @Query("SELECT oi.product.id, oi.quantity FROM OrderItem oi WHERE oi.order.id = :orderId AND oi.product IS NOT NULL")
    List<Object[]> findItemQuantities(@Param("orderId") Integer orderId);

    // ------------------------------------------------------------------ transitions

    /** Locks the order row and returns its latest committed state. Lock order: user, order, product. */
    @Query(value = "SELECT id AS id, user_id AS userId, status AS status, total_cents AS totalCents, "
            + "currency AS currency, session_id AS sessionId FROM orders WHERE id = :id FOR UPDATE",
            nativeQuery = true)
    Optional<OrderLockView> lockForTransition(@Param("id") Integer id);

    /** PENDING → CANCELLED/FAILED. The caller returns the order's units to stock only if this returns 1. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE orders SET status = :status, stock_held = 0, updated_date = NOW(6) "
            + "WHERE id = :id AND status = 'PENDING'", nativeQuery = true)
    int releaseReservation(@Param("id") Integer id, @Param("status") String status);

    /** PENDING → PAID, recording the session that paid (heals a session id that was never attached). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE orders SET status = 'PAID', session_id = :sessionId, updated_date = NOW(6) "
            + "WHERE id = :id AND status = 'PENDING'", nativeQuery = true)
    int markPaid(@Param("id") Integer id, @Param("sessionId") String sessionId);

    /** Money arrived but cannot be applied automatically. Never overrides PAID. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE orders SET status = 'NEEDS_REVIEW', review_reason = :reason, "
            + "session_id = COALESCE(session_id, :sessionId), updated_date = NOW(6) "
            + "WHERE id = :id AND status IN ('PENDING', 'CANCELLED', 'FAILED')", nativeQuery = true)
    int markNeedsReview(@Param("id") Integer id, @Param("reason") String reason,
            @Param("sessionId") String sessionId);

    /** Attaches the provider session once; a later attach (replay, reconciler) is a no-op. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE orders SET session_id = :sessionId, checkout_url = :url, updated_date = NOW(6) "
            + "WHERE id = :id AND session_id IS NULL", nativeQuery = true)
    int attachSession(@Param("id") Integer id, @Param("sessionId") String sessionId, @Param("url") String url);
}
