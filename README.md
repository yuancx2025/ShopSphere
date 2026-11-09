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
- Stripe Java SDK (v24.23.0)
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
├── backend/                         # Spring Boot REST API
│   ├── src/main/java/ecommerce/
│   │   ├── EcommerceApplication.java
│   │   ├── common/                  # Shared API response helpers
│   │   ├── config/                  # Security, CORS, Swagger
│   │   ├── controllers/             # Cart, category, product, user, wishlist
│   │   ├── dto/                     # Cart, product, user DTOs
│   │   ├── exceptions/              # Custom exceptions + GlobalExceptionHandler
│   │   ├── model/                   # Product, user, cart, category, wishlist
│   │   ├── repository/              # JPA repos for the models above
│   │   ├── security/                # JWT filter, CurrentUserService
│   │   ├── service/                 # Cart, category, product, user, wishlist
│   │   ├── order/                   # Order domain
│   │   │   ├── OrderController.java
│   │   │   ├── OrderService.java
│   │   │   ├── dto/                 # OrderResponseDto, OrderItemResponseDto
│   │   │   ├── model/               # Order, OrderItem, OrderStatus
│   │   │   └── repository/          # OrderRepository, OrderItemsRepository
│   │   └── payment/                 # Checkout / payment provider boundary
│   │       ├── PaymentGateway.java  # Provider-neutral interface
│   │       ├── CheckoutCommand.java
│   │       ├── PaymentSession.java
│   │       ├── dto/                 # CheckoutSessionResponseDto
│   │       └── stripe/              # Stripe adapter
│   │           ├── StripePaymentGateway.java
│   │           ├── StripeWebhookController.java
│   │           ├── StripeEventService.java
│   │           ├── StripeProperties.java
│   │           └── persistence/     # ProcessedStripeEvent (webhook idempotency)
│   ├── src/main/resources/
│   │   ├── application.properties
│   │   └── db/migrations/           # Flyway (V1–V3)
│   ├── pom.xml
│   └── README.md
├── frontend/react/                  # React TypeScript SPA
│   ├── src/
│   │   ├── App.tsx                  # Routes
│   │   ├── main.tsx
│   │   ├── types.ts
│   │   ├── components/
│   │   │   ├── auth/                # RequireAuth, RequireAdmin
│   │   │   ├── layout/              # Navbar, Footer
│   │   │   ├── category/            # CategoryBox
│   │   │   └── product/             # ProductBox
│   │   ├── pages/
│   │   │   ├── Home.tsx, Signin.tsx, Signup.tsx
│   │   │   ├── category/            # Admin category CRUD
│   │   │   ├── product/             # Catalog, details, wishlist, admin CRUD
│   │   │   ├── cart/
│   │   │   ├── checkout/            # Checkout + Stripe return (Success, Failed)
│   │   │   └── order/               # Order history and details
│   │   ├── utils/                   # auth.ts, constants.ts (API_BASE_URL)
│   │   └── styles/                  # CSS variables and mixins
│   ├── index.html
│   ├── vite.config.ts
│   ├── package.json
│   └── README.md
├── .github/workflows/               # backend-ci.yml, frontend-ci.yml
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
- [Benchmarks](benchmarks/README.md) - Reproducible k6 load tests, fixtures and Grafana dashboard
- [Engineering roadmap](engineering/shopsphere-reboot/README.md) - Planned sprints and benchmark contract

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
| `/order/create-checkout-session` | POST | Reserve stock and start a payment session |
| `/order/` | GET | Get order history |
| `/order/{id}` | GET | Get one order |
| `/order/session/{sessionId}` | GET | Read the order for a checkout session |
| `/order/session/{sessionId}/reconcile` | POST | Confirm payment when the webhook is delayed |
| `/webhook/stripe` | POST | Stripe webhook (signature verified, no JWT) |

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

### Payment & Checkout Learning Roadmap

The Stripe roadmap is organized around transferable payment and distributed-systems skills rather than adding isolated provider features:

1. **Reliability fundamentals**: persist a complete payment record, verify amount/currency during fulfillment, handle ambiguous network failures, and make late payments safe.
2. **Webhook engineering**: use an atomic event inbox, tolerate duplicate and out-of-order delivery, and move heavier processing to a durable worker.
3. **Deterministic testing**: test `OrderService` through `FakePaymentGateway`, use MySQL integration tests for locks and state transitions, and reserve Stripe Sandboxes/CLI for contract-level checks.
4. **Full payment lifecycle**: implement full and partial refunds, refund webhooks, payment review states, and separate refund decisions from inventory-restock decisions.
5. **Commerce features**: add eligible dynamic payment methods, shipping addresses/rates, promotion codes, explicit subtotal/discount/shipping/tax totals, and optionally Stripe Tax.
6. **Customer lifecycle**: map local users to Stripe Customers, optionally save payment methods with consent, and add payment receipts plus application-owned order confirmations.
7. **Production operations**: upgrade to the current Stripe Java client, pin/test API and webhook versions, protect keys, record provider identifiers, add observability, and reconcile stuck payments.

See [Stripe Learning and Transferable Payments Roadmap](docs/plans/stripe-learning-roadmap.md) for milestones, acceptance evidence, read order, and the concepts each step transfers to other integrations.

### Testing & Quality
- **Integration Testing**: Comprehensive API endpoint testing
- **Performance Benchmarking**: Automated latency and throughput testing
- **E2E Testing**: Frontend user flow automation with Cypress/Playwright

## 📄 License

MIT License - see [LICENSE](LICENSE) file for details.
