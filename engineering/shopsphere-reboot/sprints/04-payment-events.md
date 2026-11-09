# Sprint 4 · Stripe inbox and transactional outbox

[Roadmap](../README.md) · [Previous sprint](03-elasticsearch-search.md) · [Next sprint](05-rocketmq-workers.md)

**Time** · 2 weeks. **Depends on** · Sprint 1's payment and inventory invariants. Sprint 3 can run in parallel, but the roadmap recommends doing this sprint first.

## What to learn

- Explain how the webhook signature authenticates the raw request and why Stripe delivery can be duplicated or arrive out of order.
- Draw the inbox commit, order transition and outbox commit boundaries. Describe what survives a process failure at each boundary.
- Distinguish deduplication by Stripe event ID from idempotency at the order transition. Different event IDs can concern the same checkout.

## What to implement

- **Webhook ingestion.** Preserve raw-body signature verification in `StripeWebhookController`. Replace the check-then-record path in `StripeEventService` with a unique event-ID inbox insert. Acknowledge a valid supported delivery after durable persistence, including already persisted duplicates. Persist enough verified event data for recovery, protect it, and set a retention policy. Migrate or consult existing `processed_stripe_events` during the transition so old event replays remain safe.
- **Inbox worker.** Add a scheduled worker with bounded claims, retry state, and diagnostics for exhausted or stale work. Verify provider status through `PaymentGateway` outside long-held DB locks. Run the permitted state transition and mark inbox processing complete in one transaction where possible. Reuse the same order-transition guard for webhook and user reconciliation.
- **Outbox.** Add an `outbox_events` Flyway migration. Insert an `OrderPaid` row in the same MySQL transaction as the first paid transition, from either completion path. Persist a stable event ID and enforce uniqueness for this business event. Add a publisher interface and a test transport, with retries and publish state. Keep the real broker integration in Sprint 5.
- **Metrics and tests.** Add separate acknowledgment and processing timers, inbox/outbox age and depth, and retries. Test rollback, duplicate deliveries, out-of-order events, restart after acknowledgment, and the webhook/reconciliation race. Use a test signing key and a deterministic fake provider in load tests. Use Stripe Sandbox/CLI for a separate contract smoke check.

## Target and evidence

| Target | How to prove it |
| --- | --- |
| Zero missing accepted events or duplicate paid transitions in the test matrix | Submit an identified set of 1,000 signed test deliveries including repeated event IDs. Add distinct events for the same order, race reconciliation, and reconcile accepted IDs, transitions and outbox rows after restart. |
| P95 webhook acknowledgment under 150 ms at an initial 20 deliveries/sec | Compare the existing synchronous handler and durable inbox under the same signed input and fake provider timing. Report accepted acknowledgment and received-to-processed P95 separately. The existing handler calls Stripe during the request, so record the injected provider delay with the result. |
| Durable behavior at the commit boundaries | Fail before inbox commit, after inbox acknowledgment, and after order/outbox commit. Verify the handler and worker can resume and the first paid transition creates one outbox row. |

## Implementation PRs

| PR | Scope | Merge gate |
| --- | --- | --- |
| 09 · `feat/stripe-event-inbox` | Inbox migration, signature-preserving ingestion, worker and migration compatibility | Invalid signatures are rejected. Valid events survive restart and repeated delivery. |
| 10 · `feat/transactional-outbox` | Atomic `OrderPaid` event, test transport, publish state, crash and rollback tests | A rolled-back order update leaves no outbox event. A committed paid transition has one durable event and remains publishable after restart. |

## Résumé wording

**Reliability version.** “Built a durable Stripe webhook inbox and MySQL transactional outbox, preserving accepted events across injected restarts and recording one paid transition per order across [N] replayed test deliveries.”

**Acknowledgment version.** “Reduced Stripe webhook acknowledgment P95 from [A] ms to [B] ms at [R] deliveries/sec in a controlled test by persisting events before asynchronous processing.”

Lead with the reliability version. With a fake provider, the old handler's acknowledgment time mostly reflects the delay you inject, so choose the acknowledgment version only after measuring both versions with the same provider behavior, and state that delay. Keep payment-processing lag and the failure matrix as supporting evidence.

[Stripe webhooks](https://docs.stripe.com/webhooks)
