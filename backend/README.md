# E-Commerce Backend

Spring Boot backend API for the E-Commerce application.

## Tech Stack

- Java 17
- Spring Boot 2.5.4
- Spring Data JPA
- MySQL 8.0
- Swagger UI
- Stripe Payment Integration

## Project Structure

```
src/main/java/ecommerce/
├── EcommerceApplication.java    # Main application entry point
├── common/                      # Shared utilities and responses
├── config/                      # Configuration classes
├── controllers/                 # REST API controllers
├── dto/                         # Data Transfer Objects
├── exceptions/                  # Custom exceptions and handlers
├── model/                       # JPA Entity models
├── repository/                  # Data access repositories
└── service/                     # Business logic services
```

## Getting Started

### Prerequisites

- Java 17+
- Maven 3.6+
- MySQL 8.0+

### Configuration

1. Create a MySQL database:
```sql
CREATE DATABASE ecommerce_v2;
```

2. Update `application.properties`:
```properties
spring.datasource.url=jdbc:mysql://localhost:3306/ecommerce_v2
spring.datasource.username=your_username
spring.datasource.password=your_password
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

```bash
./mvnw test
```

## Building for Production

```bash
./mvnw clean package -DskipTests
java -jar target/ecommerce-0.0.1-SNAPSHOT.jar
```

## Configuration Profiles

- **default**: Development configuration
- **dev**: Development with verbose logging
- **prod**: Production configuration

Run with a specific profile:
```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SPRING_DATASOURCE_URL` | Database JDBC URL | `jdbc:mysql://localhost:3306/ecommerce_v2` |
| `SPRING_DATASOURCE_USERNAME` | Database username | `root` |
| `SPRING_DATASOURCE_PASSWORD` | Database password | `root` |
| `STRIPE_SECRET_KEY` | Stripe API secret key | - |
| `BASE_URL` | Application base URL | `http://localhost:8080/` |
