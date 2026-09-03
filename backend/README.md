# E-Commerce Backend

Spring Boot backend API for the E-Commerce application.

## Tech Stack

- Java 17
- Spring Boot 3.2.2
- Spring Data JPA
- PostgreSQL 16 (Neon in cloud; `postgres:16-alpine` locally)
- Flyway (single baseline migration `V1__Initial_Schema.sql`)
- Swagger UI
- Stripe Payment Integration

## Project Structure

```
src/main/java/ecommerce/
├── EcommerceApplication.java    # Main application entry point
├── common/                      # Shared utilities and responses
├── config/                      # Configuration classes
├── controllers/                 # Cart, product, category, user, wishlist APIs
├── dto/                         # Cart, product, and user DTOs
├── exceptions/                  # Custom exceptions and handlers
├── model/                       # Product, user, cart, category, wishlist entities
├── repository/                  # Data access for the models above
├── security/                    # JWT auth
├── service/                     # Cart, product, category, user, wishlist logic
├── order/                       # Order domain: controller, service, model, repository, dto
└── payment/                     # Provider-neutral checkout gateway + Stripe adapter
```

## Getting Started

### Prerequisites

- Java 17+
- Maven 3.6+
- PostgreSQL 16 (or Docker Compose / a Neon branch)

### Configuration

Point the app at Postgres via env vars. For Neon, use the **pooled** (`-pooler`) hostname for the datasource and the **direct** hostname for Flyway.

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/ecommerce_v2
spring.datasource.username=ecommerce
spring.datasource.password=ecommerce
```

### Running the Application

```bash
# Using Maven Wrapper
./mvnw spring-boot:run

# Or using Maven
mvn spring-boot:run
```

The application will start on `http://localhost:8080`

## API Documentation

Swagger UI is available at: `http://localhost:8080/swagger-ui/index.html`

## Running Tests

Tests start PostgreSQL 16 with Testcontainers (Docker required):

```bash
./mvnw test
```

## Building for Production

```bash
./mvnw clean package -DskipTests
java -jar target/ecommerce-0.0.1-SNAPSHOT.jar
```

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SPRING_DATASOURCE_URL` | Pooled JDBC URL (or local Postgres) | `jdbc:postgresql://localhost:5432/ecommerce_v2` |
| `SPRING_DATASOURCE_USERNAME` | Database username | `ecommerce` |
| `SPRING_DATASOURCE_PASSWORD` | Database password | `ecommerce` |
| `SPRING_FLYWAY_URL` | Direct (non-pooler) JDBC URL | same as datasource |
| `SPRING_FLYWAY_USER` | Flyway username | same as datasource |
| `SPRING_FLYWAY_PASSWORD` | Flyway password | same as datasource |
| `STRIPE_SECRET_KEY` | Stripe API secret key | - |
| `BASE_URL` | Application base URL | `http://localhost:8080/` |
