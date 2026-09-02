package ecommerce.order.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import ecommerce.order.model.OrderItem;

@Repository
public interface OrderItemsRepository extends JpaRepository<OrderItem,Integer> {
}
