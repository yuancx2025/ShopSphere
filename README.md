# E-Commerce Application

A full-stack e-commerce platform with comprehensive product management, shopping cart, payment processing, and order tracking capabilities. 

## Features

### Customer Features
- **User Authentication**: Secure signup and signin with token-based session management
- **Product Browsing**: View all products with detailed information and category filtering
- **Category Navigation**: Browse products organized by categories
- **Wishlist Management**: Save favorite products for future purchases
- **Shopping Cart**: Add, update quantities, and remove items with real-time calculations
- **Stripe Checkout**: Secure payment processing with Stripe integration
- **Order History**: View complete order history with detailed order breakdowns
- **Order Tracking**: Access individual order details and item information

### Admin Features
- **Category Management**: Create, update, and list product categories
- **Product Management**: Add, edit, and manage product catalog with pricing and descriptions
- **Admin Dashboard**: Centralized interface for inventory and category control

## Tech Stack

### Backend
- Java 17
- Spring Boot 3.2.2
- Spring Data JPA (Hibernate)
- MySQL 8.0
- Stripe Java SDK (v20.41.0)
- OpenAPI 3.0 / Swagger UI
- Maven build system

### Frontend
- React 18.2
- TypeScript 5.3
- Vite 5.0 (build tool)
- React Router v6 (client-side routing)
- Axios (HTTP client)
- SweetAlert2 (notifications)

### Infrastructure
- Docker & Docker Compose
- MySQL containerization
- Nginx (production deployment)

## Project Structure

```
e-commerce/
├── backend/                    # Spring Boot REST API
│   ├── src/main/java/
│   │   └── com/educative/ecommerce/
│   │       ├── controllers/    # REST endpoints
│   │       ├── service/        # Business logic
│   │       ├── model/          # JPA entities
│   │       ├── repository/     # Data access layer
│   │       ├── dto/            # Data transfer objects
│   │       ├── config/         # App configuration
│   │       └── exceptions/     # Custom exceptions
│   ├── src/main/resources/
│   │   ├── application.properties
│   │   └── db/migrations/      # Flyway migrations
│   └── pom.xml
├── frontend/react/             # React TypeScript SPA
│   ├── src/
│   │   ├── pages/              # Page components
│   │   ├── components/         # Reusable components
│   │   ├── api/                # API client & services
│   │   ├── context/            # React context (Auth, Cart)
│   │   ├── hooks/              # Custom React hooks
│   │   └── utils/              # Helper functions
│   ├── vite.config.ts
│   └── package.json
├── docs/                       # Documentation
├── docker-compose.yml
└── README.md
```

## Quick Start

### Prerequisites
- Java 17+
- Node.js 18+
- MySQL 8.0+
- Docker (optional)
- Stripe account for payment processing

### Using Docker Compose
```bash
docker compose up -d
```
Access: Frontend at `http://localhost:3000`, API at `http://localhost:8080`

### Manual Setup
See [docs/SETUP.md](docs/SETUP.md) for detailed configuration.

## Documentation

- [API Documentation](docs/API.md) - Complete REST API reference
- [Setup Guide](docs/SETUP.md) - Installation and configuration
- [Deployment Guide](docs/DEPLOYMENT.md) - Production deployment strategies

## Key API Endpoints

Base URL: `http://localhost:8080`

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/user/signup` | POST | Register new customer |
| `/user/signIn` | POST | Authenticate user |
| `/product/` | GET | List all products |
| `/product/add` | POST | Add product (admin) |
| `/category/` | GET | List categories |
| `/category/create` | POST | Create category (admin) |
| `/cart/` | GET | Get user's cart |
| `/cart/add` | POST | Add item to cart |
| `/wishlist/` | GET | Get user's wishlist |
| `/order/create-checkout-session` | POST | Initialize Stripe payment |
| `/order/add` | POST | Place order |
| `/order/` | GET | Get order history |

See [API Documentation](docs/API.md) for authentication and request/response details.

## Building for Production

### Backend
```bash
cd backend
./mvnw clean package -DskipTests
java -jar target/ecommerce-0.0.1-SNAPSHOT.jar
```

### Frontend
```bash
cd frontend/react
npm run build
# Outputs to dist/ directory
```

## Currently Working On..

### Security & Authentication
- **JWT Token Implementation**: Migrate from custom token system to industry-standard JWT for improved security and stateless authentication
- **OAuth2 Integration**: Add social login (Google, GitHub) for enhanced user experience

### Performance Optimization
- **Redis Caching Layer**: Cache product catalog and category lists to reduce database load and improve response times
- **Database Indexing**: Optimize queries with strategic indexes on frequently accessed columns

### Search & Discovery
- **OpenSearch/Elasticsearch**: Implement full-text search with filters, facets, and relevance ranking
- **Advanced Product Filtering**: Price ranges, ratings, availability, and multi-attribute filtering

### Scalability & Reliability
- **Asynchronous Order Processing**: Decouple order placement using message queues (AWS SQS/RabbitMQ)
  - Send orders to queue for processing
  - Background workers update order status
  - Automated email notifications
- **Performance Monitoring**: Track and optimize P95/P99 latency metrics
- **Load Testing**: Establish baseline performance under concurrent user load

### Payment & Checkout
- **Enhanced Stripe Integration**: Implement webhooks for payment status updates and refund handling
- **Multiple Payment Methods**: Support for PayPal, Apple Pay, Google Pay

### Testing & Quality
- **Integration Testing**: Comprehensive API endpoint testing
- **Performance Benchmarking**: Automated latency and throughput testing
- **E2E Testing**: Frontend user flow automation with Cypress/Playwright

## 📄 License

MIT License - see [LICENSE](LICENSE) file for details.
