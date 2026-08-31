-- Flyway V1: schema owned by SQL, table names match JPA @Table annotations.
-- Hibernate ddl-auto=validate checks these columns exist; it does not create them.

CREATE TABLE categories (
    id INT AUTO_INCREMENT PRIMARY KEY,
    category_name VARCHAR(255) NOT NULL,
    description VARCHAR(255) NOT NULL,
    image_url VARCHAR(255) NOT NULL
);

CREATE TABLE users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    email VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL DEFAULT 'USER',
    CONSTRAINT uk_users_email UNIQUE (email),
    INDEX idx_user_email (email)
);

CREATE TABLE products (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    image_url VARCHAR(255) NOT NULL,
    price DOUBLE NOT NULL,
    description VARCHAR(255) NOT NULL,
    category_id INT NOT NULL,
    CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories(id),
    INDEX idx_product_category (category_id),
    INDEX idx_product_name (name)
);

CREATE TABLE cart (
    id INT AUTO_INCREMENT PRIMARY KEY,
    created_date DATETIME(6),
    product_id INT NOT NULL,
    user_id INT NOT NULL,
    quantity INT NOT NULL,
    CONSTRAINT fk_cart_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT unique_cart_item UNIQUE (user_id, product_id),
    INDEX idx_cart_user (user_id)
);

CREATE TABLE wishlist (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    created_date DATETIME(6),
    product_id INT,
    CONSTRAINT fk_wishlist_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_wishlist_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE,
    CONSTRAINT unique_wishlist_item UNIQUE (user_id, product_id),
    INDEX idx_wishlist_user (user_id)
);

CREATE TABLE orders (
    id INT AUTO_INCREMENT PRIMARY KEY,
    created_date DATETIME(6),
    total_price DOUBLE,
    session_id VARCHAR(255),
    user_id INT,
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_order_user (user_id)
);

CREATE TABLE orderitems (
    id INT AUTO_INCREMENT PRIMARY KEY,
    quantity INT NOT NULL,
    price DOUBLE NOT NULL,
    created_date DATETIME(6),
    order_id INT,
    product_id INT,
    CONSTRAINT fk_orderitems_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_orderitems_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE SET NULL,
    INDEX idx_order_item_order (order_id)
);

INSERT INTO categories (category_name, description, image_url) VALUES
('Electronics', 'Electronic devices and accessories', 'https://images.unsplash.com/photo-1498049794561-7780e7231661?w=300'),
('Clothing', 'Fashion and apparel', 'https://images.unsplash.com/photo-1445205170230-053b83016050?w=300'),
('Books', 'Books and publications', 'https://images.unsplash.com/photo-1495446815901-a7297e633e8d?w=300'),
('Home & Garden', 'Home decor and garden supplies', 'https://images.unsplash.com/photo-1484154218962-a197022b5858?w=300');
