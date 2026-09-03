# Stripe Learning and Transferable Payments Roadmap

## Goal

Use ShopSphere's Stripe integration as a small payment system for learning durable engineering ideas, not as a checklist of Stripe-specific features.

At the end of this roadmap, the implementation should demonstrate that the developer can:

- keep business rules independent from a payment vendor;
- reason about uncertain network outcomes and safe retries;
- authenticate, deduplicate, order, and replay external events;
- model payment and order state transitions explicitly;
- coordinate database transactions with external APIs through compensation and reconciliation;
- test concurrency without calling a real payment provider;
- operate the integration with useful logs, metrics, alerts, and recovery procedures;
- decide when Stripe Checkout, Billing, Connect, or a custom payment UI is appropriate.

## Current baseline

The repository already demonstrates several sound boundaries:

- `OrderService` owns stock reservation and the local order lifecycle.
- `PaymentGateway` is the provider-neutral port used by order code.
- `StripePaymentGateway` translates neutral checkout commands into Stripe Checkout Sessions.
- Stripe-hosted Checkout keeps raw card entry outside the application.
- Checkout creation uses a stable idempotency key derived from the local order ID.
- `StripeWebhookController` verifies the raw payload and `Stripe-Signature`.
- Successful, asynchronous-success, asynchronous-failure, and expiration events are handled.
- The success page uses one explicit reconciliation command followed by read-only polling.
- Pessimistic order and product locks protect important inventory and terminal-state transitions.

The current baseline is a good starting point, but a payment is represented mainly by `Order.status` and `Order.sessionId`. The next steps should deepen correctness and operational evidence before broadening features.

## Learning principle: implement one invariant at a time

For every milestone, write down four things before coding:

1. **Invariant** — what must always remain true?
2. **Failure** — what interruption, duplicate, timeout, or race could violate it?
3. **Mechanism** — what transaction, lock, idempotency key, event record, or compensating action protects it?
4. **Evidence** — what automated test or runtime trace proves it?

Example:

> Invariant: one successful Checkout Session reduces inventory once. Failure: Stripe delivers the same event twice while the success page also reconciles. Mechanism: lock the order row and allow only `PENDING -> PAID`. Evidence: a concurrency test starts both operations and asserts one transition and one inventory result.

## Stage 1: Provider client, money, and uncertain outcomes

### Implement

- Replace global `Stripe.apiKey` and static resource calls with an injected `StripeClient` inside `StripePaymentGateway`.
- Upgrade Stripe Java in an isolated change after reviewing every intervening major-version migration guide.
- Configure conservative connect/read timeouts and a small automatic network retry count.
- Continue sending the same order-derived idempotency key when retrying Checkout Session creation.
- Replace `double`/MySQL `DOUBLE` money with integer minor units or `BigDecimal`/`DECIMAL`.
- Add a durable `Payment` record containing at least:
  - provider;
  - Checkout Session ID;
  - PaymentIntent ID;
  - currency;
  - expected amount;
  - provider-reported amount;
  - provider payment status;
  - paid/refunded timestamps;
  - last relevant provider event ID.
- During fulfillment, verify payment status, amount, currency, and order metadata before changing the order to `PAID`.
- Stop treating session expiration as an ignorable best-effort call. Do not release stock until remote expiration is confirmed or a safe recovery policy takes ownership.
- Add explicit states such as `CANCELLATION_PENDING` and `PAYMENT_REVIEW` for ambiguous or contradictory outcomes.

### Learn

- Idempotency is what makes retry safe; retry by itself can duplicate work.
- A timeout means "unknown outcome," not necessarily "failure."
- A database transaction cannot atomically include Stripe, so external workflows require compensation or reconciliation.
- Money is a domain value with currency and exact arithmetic, not a floating-point number.

### Evidence gate

- A lost response after Stripe creates a session produces one Stripe session and one local payment record.
- A failed expiration never creates a locally cancelled but still-payable order without a recovery state.
- Fulfillment rejects mismatched currency, amount, or order metadata.
- Database migrations preserve existing order totals exactly.

## Stage 2: Webhook inbox and event-driven processing

### Implement

- Replace the separate `alreadyProcessed` / business action / `recordProcessedEvent` sequence with a durable event-inbox workflow.
- Store the event ID, type, object ID, received time, processing status, attempt count, last error, and completion time.
- Atomically claim an event for processing using a unique constraint and transaction.
- Make each order transition independently idempotent; do not rely only on event-ID deduplication.
- Define valid state transitions and precedence for contradictory or out-of-order events.
- Keep raw-body signature verification at the HTTP boundary.
- Configure the Stripe webhook endpoint to send only required event types.
- Once deployed with a queue, verify and durably enqueue/store the event before returning `2xx`; process it in a worker with retry and dead-letter handling.

### Learn

- Webhooks are untrusted public input until signature verification succeeds.
- Delivery is at least once: duplicates, delays, and reordering are normal.
- Deduplication answers "have I seen this event?" while idempotent domain logic answers "is this transition still valid?"
- A durable inbox separates accepting an event from completing its business effects.

### Transfer

The same design applies to GitHub webhooks, Shopify events, Twilio callbacks, cloud notifications, and message-queue consumers.

### Evidence gate

- Duplicate deliveries result in one domain transition.
- Two concurrent deliveries cannot both claim the event.
- Reordered success/expiration events follow the documented state policy.
- A worker crash after claim is recoverable without data loss or double fulfillment.

## Stage 3: Deterministic payment and concurrency tests

### Implement

- Add `FakePaymentGateway` under test sources with controllable create, retrieve, expire, timeout, and paid/unpaid outcomes.
- Unit-test `OrderService` without Stripe network calls.
- Use Testcontainers with MySQL for pessimistic-lock, unique-constraint, migration, and transaction tests.
- Test simultaneous checkout, webhook, reconciliation, expiration, and refund commands.
- Use a Stripe Sandbox and Stripe CLI only for provider contract tests and a small end-to-end smoke suite.
- Test success, decline, 3D Secure, asynchronous completion, refund, duplicate webhook, and malformed signature scenarios.
- Load-test the application with the fake gateway; do not load-test Stripe's testing environment.

### Learn

- Ports and adapters make nondeterministic integrations testable.
- Unit tests prove business decisions; database integration tests prove locking and transaction behavior; sandbox tests prove the provider contract.
- Fault injection is often more valuable than another happy-path test.

### Evidence gate

- Tests deterministically reproduce every payment state transition.
- A concurrency test proves stock and cart effects happen exactly once.
- CI runs without Stripe credentials except for a separately controlled sandbox smoke job.

## Stage 4: Refunds and the complete payment lifecycle

### Implement

- Extend `PaymentGateway` with full and partial refund operations and provider-neutral results.
- Use an idempotency key for every refund command.
- Add `REFUND_PENDING`, `PARTIALLY_REFUNDED`, `REFUNDED`, and `REFUND_FAILED` payment states.
- Handle `refund.created`, `refund.updated`, and `refund.failed` webhook events.
- Add an admin-authorized refund endpoint and record the actor, reason, amount, and provider identifiers.
- Keep refund and inventory-restock decisions separate. A damaged return, lost shipment, and pre-fulfillment cancellation do not have the same stock result.
- Add an explicit paid-after-local-cancellation workflow: fulfill, refund, or place in manual review—never silently ignore it.

### Learn

- Payment state and order-fulfillment state are related but different state machines.
- Refund requests are asynchronous workflows, not simple boolean updates.
- Compensating transactions are business decisions that need auditability.

### Evidence gate

- Duplicate refund requests create one provider refund.
- Partial refunds accumulate without exceeding the captured amount.
- Refund webhook replay is harmless.
- Inventory changes only under the documented return/restock policy.

## Stage 5: Commerce totals—shipping, discounts, and tax

Implement these in order because each changes the final amount:

1. Collect and snapshot the shipping address on the order.
2. Offer shipping rates and persist the selected method and amount.
3. Enable promotion codes and persist discount details.
4. Represent subtotal, discount, shipping, tax, and final total separately.
5. Optionally enable Stripe Tax after product tax codes, address rules, and registration decisions are understood.

### Learn

- The cart subtotal and captured payment amount are not interchangeable.
- Provider-calculated values must be copied into an application-owned order snapshot.
- Adding a feature at the payment page changes reconciliation, refunds, reporting, and tests downstream.

### Evidence gate

- The order confirmation explains every component of the charged amount.
- The stored final total equals Stripe's final amount.
- Refund calculations use the captured total rather than an earlier cart estimate.

## Stage 6: Dynamic methods, Customers, and receipts

### Implement

- First use Stripe Dashboard-managed dynamic payment methods and test which methods are eligible for the configured currency and customer location.
- Add `stripe_customer_id` to the local user only when repeat-customer behavior is required.
- Reuse the Customer during Checkout to associate purchases and prefill supported information.
- Offer saved payment methods only with explicit customer consent and a clear future-use policy.
- Enable Stripe payment receipts and send a separate ShopSphere order confirmation after fulfillment.

### Learn

- A local user and a payment-provider customer are separate identities that require explicit mapping.
- Payment method eligibility and completion timing vary by region, currency, and method.
- A payment receipt proves payment; an order confirmation communicates fulfillment.

### Evidence gate

- Returning users are mapped to one provider customer.
- Delayed payment methods do not trigger fulfillment while unpaid.
- Receipt and order-confirmation delivery are independently observable and retryable.

## Stage 7: Production operations and reconciliation

### Implement

- Store Stripe secrets only in environment/secret management; validate required production configuration at startup.
- Pin and test API/webhook versions rather than depending silently on account defaults.
- Log order ID, session ID, PaymentIntent ID, event ID, transition, and retry count without logging secrets or full sensitive payloads.
- Add metrics for checkout creation failure, webhook verification failure, processing lag, retries, payment-state age, refund failure, and reconciliation results.
- Add alerts for stuck `PENDING`, `CANCELLATION_PENDING`, `PAYMENT_REVIEW`, and `REFUND_PENDING` records.
- Run a scheduled reconciliation job that compares old nonterminal local payments with Stripe and records every repair.
- Document key rotation, webhook-secret rotation, failed-event replay, manual refund, and late-payment procedures.
- In the AWS phase, use durable queueing, a dead-letter queue, least-privilege secrets access, and controlled outbound access to Stripe.

### Learn

- Reliability includes detection and recovery, not only preventing errors.
- Reconciliation repairs divergence between two systems that cannot share one transaction.
- Runbooks turn implicit developer knowledge into an operable system.

### Evidence gate

- A deliberately stuck payment triggers an alert and is repaired by reconciliation.
- A failed event appears in a dead-letter workflow and can be replayed safely.
- Secret rotation succeeds without source changes.
- Logs can trace one checkout from local order creation through provider event and final state.

## Optional specialization tracks

Choose these only when they answer a product question:

| Product direction | Stripe capability | New concepts learned |
|---|---|---|
| Memberships or recurring boxes | Billing and subscriptions | renewal state, invoices, proration, dunning, test clocks |
| Multi-seller marketplace | Connect | account onboarding, KYC, destination charges, transfers, platform liability |
| Strong need for on-site payment UX | Embedded Checkout or Elements | client/server confirmation, payment-element lifecycle, broader frontend security surface |
| Deposits or later off-session charges | Saved methods and SetupIntents | consent, mandates, off-session authentication, recovery flows |

Hosted Checkout remains the right default for the current product. Do not switch UI approaches merely to add code.

## Transfer map

| Stripe implementation idea | General engineering concept | Other contexts |
|---|---|---|
| Checkout Session | Outsource a sensitive hosted workflow | OAuth login, hosted identity verification, document signing |
| `PaymentGateway` | Ports and adapters / dependency inversion | email providers, object storage, shipping carriers, market-data vendors |
| Idempotency key | Retry-safe command identity | order submission, job creation, provisioning APIs |
| Webhook signature | Authenticating inbound callbacks | GitHub, Shopify, Twilio, Slack |
| Event ID and inbox | Deduplication and durable event consumption | Kafka, SQS, Pub/Sub, change-data capture |
| Order/product row locks | Serializing contested state transitions | reservations, seat allocation, account balances |
| Payment state machine | Explicit lifecycle and transition invariants | fulfillment, approvals, onboarding, deployment workflows |
| Refund/stock compensation | Saga-style recovery | travel booking, distributed provisioning, warehouse workflows |
| Reconciliation job | Repairing eventual consistency | billing ledgers, cloud resources, broker/exchange positions |
| Provider request/event IDs | Cross-system observability | third-party APIs, distributed tracing, incident response |

## Recommended study and implementation order

1. Draw the current checkout sequence and identify the source of truth for user, price, stock, order state, and payment state.
2. Implement exact money and the durable payment record.
3. Fix ambiguous session creation/expiration outcomes and late-payment policy.
4. Add the fake gateway and database concurrency tests.
5. Upgrade Stripe Java, migrate to `StripeClient`, and align webhook/API versions.
6. Replace webhook check-then-record with an atomic inbox.
7. Implement refunds and separate refund from restock policy.
8. Add shipping, discounts, monetary breakdowns, then tax.
9. Add Customer reuse and optional saved methods.
10. Add queueing, observability, alerts, reconciliation, and runbooks.
11. Choose one optional specialization track only after the core lifecycle is proven.

## Repository read order

Use this order when reviewing or explaining the implementation:

1. `frontend/react/src/pages/checkout/Checkout.tsx`
2. `backend/src/main/java/ecommerce/order/OrderController.java`
3. `backend/src/main/java/ecommerce/order/OrderService.java`
4. `backend/src/main/java/ecommerce/payment/PaymentGateway.java`
5. `backend/src/main/java/ecommerce/payment/CheckoutCommand.java`
6. `backend/src/main/java/ecommerce/payment/PaymentSession.java`
7. `backend/src/main/java/ecommerce/payment/stripe/StripePaymentGateway.java`
8. `backend/src/main/java/ecommerce/payment/stripe/StripeWebhookController.java`
9. `backend/src/main/java/ecommerce/payment/stripe/StripeEventService.java`
10. `backend/src/main/java/ecommerce/order/repository/OrderRepository.java`
11. `backend/src/main/resources/db/migrations/V3__Inventory_And_Payment.sql`
12. `frontend/react/src/pages/checkout/Success.tsx`

For each file, ask: what does it own, what does it trust, what can fail, what must be idempotent, and how is success proven?

## Official references

- [Stripe Checkout](https://docs.stripe.com/payments/checkout)
- [Fulfill Checkout orders](https://docs.stripe.com/checkout/fulfillment)
- [Webhook best practices](https://docs.stripe.com/webhooks)
- [Idempotent requests](https://docs.stripe.com/api/idempotent_requests?lang=java)
- [Stripe Java client](https://github.com/stripe/stripe-java)
- [API upgrades](https://docs.stripe.com/upgrades)
- [Webhook versioning](https://docs.stripe.com/webhooks/versioning)
- [Testing](https://docs.stripe.com/testing)
- [Refunds](https://docs.stripe.com/refunds)
- [Checkout payment methods](https://docs.stripe.com/payments/checkout/payment-methods)
- [Shipping addresses](https://docs.stripe.com/payments/collect-addresses)
- [Checkout discounts](https://docs.stripe.com/payments/checkout/discounts)
- [Stripe Tax with Checkout](https://docs.stripe.com/payments/checkout/taxes)
- [Secret-key management](https://docs.stripe.com/keys-best-practices)
