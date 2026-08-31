package ecommerce.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import ecommerce.model.Cart;
import ecommerce.model.Product;
import ecommerce.model.User;

@Repository
public interface CartRepository extends JpaRepository<Cart, Integer> {
    List<Cart> findAllByUserOrderByCreatedDateDesc(User user);

    void deleteByUser(User user);

    Optional<Cart> findByUserAndProduct(User user, Product product);

    Optional<Cart> findByIdAndUser(Integer id, User user);
}
