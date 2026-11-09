package ecommerce.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import ecommerce.support.MySqlIntegrationTest;

/**
 * V4 validates legacy DOUBLE money before switching to cents: whole-cent values backfill exactly, and a
 * sub-cent legacy price fails the migration instead of being rounded silently. Each case migrates a fresh
 * schema to V3, inserts legacy rows, then applies the rest.
 */
class MoneyMigrationTest extends MySqlIntegrationTest {

    @Test
    void legacyDoublesBackfillToExactCents() throws Exception {
        String url = freshSchemaAtV3("legacy_money_ok");
        try (Connection c = DriverManager.getConnection(url, "root", MYSQL.getPassword());
                Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO products (id, name, image_url, price, description, category_id, stock) VALUES "
                    + "(1, 'A', 'u', 19.99, 'd', 1, 5), (2, 'B', 'u', 0.1, 'd', 1, 5), (3, 'C', 'u', 1234.5, 'd', 1, 5)");
            s.executeUpdate("INSERT INTO users (id, email, password, role) VALUES (50, 'legacy@x.test', 'p', 'USER')");
            // A legacy total summed from doubles: 3 * 19.99 + 0.1 + 2 * 0.2 = 60.47000000000001
            s.executeUpdate("INSERT INTO orders (id, total_price, user_id, status) VALUES (1, "
                    + (3 * 19.99 + 0.1 + 2 * 0.2) + ", 50, 'PAID')");
            s.executeUpdate("INSERT INTO orderitems (quantity, price, order_id, product_id, product_name) VALUES "
                    + "(3, 19.99, 1, 1, 'A'), (1, 0.1, 1, 2, 'B'), (2, 0.2, 1, 2, 'B')");
        }

        flyway(url, null).migrate();

        try (Connection c = DriverManager.getConnection(url, "root", MYSQL.getPassword());
                Statement s = c.createStatement()) {
            assertThat(longs(s, "SELECT price_cents FROM products ORDER BY id")).containsExactly(1999L, 10L, 123450L);
            assertThat(longs(s, "SELECT total_cents FROM orders")).containsExactly(6047L);
            assertThat(longs(s, "SELECT unit_price_cents FROM orderitems ORDER BY id")).containsExactly(1999L, 10L, 20L);
            assertThat(longs(s, "SELECT stock_held FROM orders")).containsExactly(1L);
        }
    }

    @Test
    void subCentLegacyPriceFailsTheMigration() throws Exception {
        String url = freshSchemaAtV3("legacy_money_bad");
        try (Connection c = DriverManager.getConnection(url, "root", MYSQL.getPassword());
                Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO products (id, name, image_url, price, description, category_id, stock) VALUES "
                    + "(1, 'A', 'u', 19.99, 'd', 1, 5), (2, 'Sub-cent', 'u', 19.999, 'd', 1, 5)");
        }

        assertThatThrownBy(() -> flyway(url, null).migrate())
                .hasStackTraceContaining("chk_v4_products_price_whole_cents");
    }

    private static String freshSchemaAtV3(String schema) throws Exception {
        String base = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(MySQLContainer.MYSQL_PORT);
        try (Connection c = DriverManager.getConnection(base + "/?allowPublicKeyRetrieval=true&useSSL=false", "root",
                MYSQL.getPassword()); Statement s = c.createStatement()) {
            s.executeUpdate("DROP DATABASE IF EXISTS " + schema);
            s.executeUpdate("CREATE DATABASE " + schema);
        }
        String url = base + "/" + schema + "?allowPublicKeyRetrieval=true&useSSL=false";
        flyway(url, "3").migrate();
        return url;
    }

    private static Flyway flyway(String url, String target) {
        var config = Flyway.configure()
                .dataSource(url, "root", MYSQL.getPassword())
                .locations("classpath:db/migrations");
        if (target != null) {
            config.target(MigrationVersion.fromVersion(target));
        }
        return config.load();
    }

    private static java.util.List<Long> longs(Statement s, String sql) throws Exception {
        java.util.List<Long> values = new java.util.ArrayList<>();
        try (ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getLong(1));
            }
        }
        return values;
    }
}
