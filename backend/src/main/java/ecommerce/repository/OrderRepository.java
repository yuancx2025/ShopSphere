package ecommerce.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import ecommerce.model.Order;
import ecommerce.model.OrderStatus;
import ecommerce.model.User;

@Repository
public interface OrderRepository extends JpaRepository<Order, Integer> {

    List<Order> findAllByUserAndStatus(User user, OrderStatus status);

    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.orderItems WHERE o.user = :user AND o.status <> :excluded")
    List<Order> findAllVisibleWithItems(@Param("user") User user, @Param("excluded") OrderStatus excluded);

    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.orderItems WHERE o.id = :id")
    Optional<Order> findByIdWithItems(@Param("id") Integer id);

    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.orderItems WHERE o.sessionId = :sessionId")
    Optional<Order> findBySessionIdWithItems(@Param("sessionId") String sessionId);
}
