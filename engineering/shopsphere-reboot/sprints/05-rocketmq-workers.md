# Sprint 5 · RocketMQ and asynchronous workers

[Roadmap](../README.md) · [Previous sprint](04-payment-events.md) · [Next sprint](06-traffic-protection.md)

**Time** · 2–3 weeks. **Depends on** · A durable `OrderPaid` outbox with stable event IDs from Sprint 4.

## What to learn

- Trace one event from outbox row to broker, consumer group, retry, and dead-letter queue.
- Explain at-least-once delivery and the failure window between broker acceptance and the publisher recording success.
- Explain what makes a consumer side effect idempotent and how the guarantee changes when a real external email provider is involved.

## What to implement

- **Broker and producer.** Add a local RocketMQ Compose profile and compatible, pinned Java client. Publish versioned `OrderPaid` messages with stable outbox event IDs. Record publication only after broker acceptance and let retries reuse the same ID. Bound publish time, batches and retries.
- **Consumer.** Add a notification worker that writes an application-owned confirmation record or simulated notification. Commit the dedup marker and simulated effect in one MySQL transaction, then acknowledge the message. Keep the order state and inventory authority in MySQL. Measure consumer success independently of checkout acceptance.
- **Recovery.** Configure the chosen consumer's retry limits and dead-letter handling. Add an operator replay command that preserves the event ID, logs the replay outcome and handles a poison message after its cause is corrected.
- **Workload and telemetry.** Create an outbox fixture for 10,000 unique events and label it as a pipeline benchmark. A smaller flow should start with fake-provider checkout and end at the confirmation record. Collect unique effects/sec, oldest pending age, queue depth, and P95 from outbox creation to durable effect. Record publish-to-consume time separately.

## Target and evidence

| Target | How to prove it |
| --- | --- |
| Initial capacity goal of 50 unique events/sec with P95 outbox-to-effect lag under 2 seconds | Sustain a staged input rate during a healthy steady-state window and confirm that backlog is stable. Count unique committed effects per second, with input and completion rates reported separately. |
| Zero missing expected effects or duplicate effects in the tested fixture | Reconcile 10,000 expected outbox IDs against committed effects. Include duplicate deliveries and crash windows on both the producer and consumer side. |
| Recovery within 120 seconds after the worker restarts in a bounded scenario | Stop the worker for 30 seconds at a documented input rate, restore it with a specified continuing rate, then time to pre-fault backlog. Track dead-letter items and verify replay after fixing the poison case. |

## Implementation PRs

| PR | Scope | Merge gate |
| --- | --- | --- |
| 11 · `feat/async-order-workers` | RocketMQ profile, outbox publisher, event contract and consumer | A fake-provider paid order produces a single simulated confirmation. An outage leaves pending outbox rows recoverable. |
| 12 · `test/messaging-reliability` | Failure injection, retries, dead-letter replay, benchmark and reconciliation | The report contains healthy and faulted runs with unique event IDs, final reconciliation, processing lag and recovery time. |

## Résumé wording

**Throughput version.** “Built a RocketMQ order-event pipeline with a MySQL transactional outbox and idempotent workers, processing [R] unique events/sec at [L] s P95 outbox-to-confirmation lag in synthetic tests.”

**Reliability version.** “Validated RocketMQ retry and dead-letter recovery across [N] synthetic order events, observing zero duplicate simulated confirmations after injected worker crashes and event replay.”

Use unique committed effects for `[R]` and state where timing starts and ends. Use “simulated confirmations” when the consumer writes the test sink. Keep the broker workload separate from any claimed checkout throughput.

[RocketMQ consumption retries](https://rocketmq.apache.org/docs/featureBehavior/10consumerretrypolicy/)
