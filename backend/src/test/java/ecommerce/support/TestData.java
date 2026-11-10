package ecommerce.support;

import java.math.BigDecimal;

import org.springframework.jdbc.core.JdbcTemplate;

/** Inserts fixture rows with plain SQL so tests control ids and never go through the code under test. */
public final class TestData {

    public static final int CATEGORY_ID = 1;
    // bcrypt of "test-password"; these users never sign in through HTTP in the integration tests.
    private static final String PASSWORD_HASH = "$2a$10$A1zDzXSde3EKQDNxsJ3zX.DhZSzKphT9bbhzvMevrau/cumkCUS7G";

    private final JdbcTemplate jdbc;

    public TestData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Empties every table the tests touch and recreates one category. */
    public TestData reset() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : new String[] {"orderitems", "orders", "cart", "wishlist", "processed_stripe_events",
                "consumed_events", "search_index_jobs", "products", "categories"}) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
        jdbc.update("DELETE FROM users WHERE email LIKE '%@it.shopsphere.test'");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        category(CATEGORY_ID, "Test");
        return this;
    }

    public void category(int id, String name) {
        jdbc.update("INSERT INTO categories (id, category_name, description, image_url) VALUES (?, ?, ?, ?)",
                id, name, "Integration test category " + name, "https://images.shopsphere.test/c" + id + ".jpg");
    }

    public void product(int id, String name, String price, int stock) {
        product(id, CATEGORY_ID, name, price, stock);
    }

    public void product(int id, int categoryId, String name, String price, int stock) {
        product(id, categoryId, name, "Test product " + id, price, stock);
    }

    public void product(int id, int categoryId, String name, String description, String price, int stock) {
        BigDecimal dollars = new BigDecimal(price);
        jdbc.update("INSERT INTO products (id, name, image_url, price, price_cents, description, category_id, stock) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, name, "https://images.shopsphere.test/p" + id + ".jpg", dollars.doubleValue(),
                dollars.movePointRight(2).longValueExact(), description, categoryId, stock);
    }

    /** Creates users with ids firstId..firstId+count-1. */
    public void users(int firstId, int count) {
        for (int n = 0; n < count; n++) {
            int id = firstId + n;
            jdbc.update("INSERT INTO users (id, first_name, last_name, email, password, role) "
                    + "VALUES (?, 'IT', ?, ?, ?, 'USER')", id, "User" + id, "user" + id + "@it.shopsphere.test",
                    PASSWORD_HASH);
        }
    }

    public void cartLine(int userId, int productId, int quantity) {
        jdbc.update("INSERT INTO cart (created_date, product_id, user_id, quantity) VALUES (NOW(6), ?, ?, ?)",
                productId, userId, quantity);
    }

    public int stock(int productId) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId);
    }

    /** Units of a product held by orders (reserved or sold). */
    public int heldUnits(int productId) {
        return count("SELECT COALESCE(SUM(oi.quantity), 0) FROM orderitems oi JOIN orders o ON o.id = oi.order_id "
                + "WHERE o.stock_held = 1 AND oi.product_id = ?", productId);
    }

    public int cartLines(int userId) {
        return count("SELECT COUNT(*) FROM cart WHERE user_id = ?", userId);
    }

    public String orderStatus(int orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    public int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    public int hikariMaxPoolSize() {
        return jdbc.getDataSource() instanceof com.zaxxer.hikari.HikariDataSource hikari
                ? hikari.getMaximumPoolSize() : -1;
    }
}
