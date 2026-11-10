# Sprint 6 · Traffic protection

[Roadmap](../README.md) · [Previous sprint](05-rocketmq-workers.md) · [Next sprint](07-aws-final-evaluation.md)

**Time** · 1–2 weeks. **Depends on** · Redis integration and the checkout harness. Run after the other main features to exercise the final shopping mix.

## What to learn

- Explain token-bucket refill, burst capacity, and why an atomic shared operation matters across API replicas.
- Separate successful goodput, intentional 429s, 5xx, timeouts, and expected business rejections. Explain how latency can look better if most traffic is rejected.
- Distinguish protecting a checkout budget from protecting shared CPU and database capacity. Select limits based on a measured load envelope.

## What to implement

- **Admission policy.** Add a Redis-backed token bucket using one atomic server-side operation per decision. Start with separate discovery (catalog/search) and checkout policies. Define identity, burst size, refill and timeout behavior for each policy, and return `429` with `Retry-After`. Keep checkout authentication in place. "Checkout" here is the stock reservation against the fake payment gateway; payment flows are deferred. Keep the webhook route outside shopper buckets.
- **Aggregate budget and identity.** Per-identity buckets limit individual clients but do not cap total load when a burst comes from many identities. Add a global discovery budget, sized from the measured capacity, so checkout keeps its share of CPU and database capacity. k6 sends everything from one IP, so identify shoppers with per-VU tokens or a test identity header that Nginx sets and strips from client requests.
- **Failure behavior.** Give Redis decisions a short timeout. Explicitly test and document the policy on Redis failure. A bounded per-instance fallback can protect public reads if its aggregate budget accounts for replica count. Keep checkout failure handling explicit so a limiter outage does not silently turn off protection or masquerade as an inventory rejection.
- **Infrastructure.** Add a two-API-replica Compose profile behind Nginx for the distributed-budget test. Verify that the same seeded identity draws from one bucket even when requests hit both replicas. Keep the same topology with the limiter disabled for the A/B run.
- **Workload.** Establish normal mixed capacity first. Reuse a fixed trace with regular checkout arrivals and a surge on catalog/search. Start at a passing steady load, burst to roughly 2–3× that load for 60 seconds, then return to baseline. Use a separate hot-SKU run to check inventory after contention. Record per-route P95, achieved successful goodput, 429s, expected business rejections, 5xx and timeouts in each phase. Set an explicit k6 request timeout, for example 2 seconds, so the limiter-off run shows overload as failures rather than slow successes.

## Target and evidence

| Target | How to prove it |
| --- | --- |
| Provisional goal of ≥95% of the protected checkout arrival rate succeeding during a browse burst | Give checkout enough stock in this overload test. Keep its offered rate below its measured normal capacity. Compare success counts and accepted P95 with the limiter on and off under the same burst. |
| Fewer than 1% unexpected failures during the protected run, with bounded and visible admission rejections | Count timeouts and connection errors alongside unexpected HTTP failures. Report 429s by route and verify admission rejections fall primarily on the intended traffic. |
| Recovery and inventory consistency | Time the return to the pre-burst latency range. In the separate stock-contention run, reconcile available, reserved, and sold units, and verify zero oversells. |

The system capacity figure still comes from the steady-state gate in the [roadmap](../README.md). The overload experiment establishes how the system behaves beyond that envelope.

## Implementation PR

| PR | Scope | Merge gate |
| --- | --- | --- |
| 13 · `feat/traffic-protection` | Redis limiter, explicit route policies and outage behavior, replica setup and burst tests | Both replicas respect the shared budget. Matched traces quantify protected checkout success, 429s, unexpected errors, recovery time and stock consistency. |

## Résumé wording

**Protected traffic version.** “Implemented Redis-backed rate limiting across two Spring Boot replicas, maintaining [X]% checkout success during a [R]-RPS synthetic browse/search spike with [Y]% unexpected failures.”

**Improvement version.** “Reduced unexpected failures from [A]% to [B]% during a matched [R]-RPS load spike using Redis-backed request admission, while serving [G] successful checkout requests/sec.”

Use the exact offered or delivered basis for `[R]` and state it in the supporting report. Pair error reduction with checkout success or goodput and retain the 429 count for the interview.
