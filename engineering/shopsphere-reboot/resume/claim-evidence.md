# ShopSphere · Résumé claim evidence

[Roadmap](../README.md) · [Target résumé entries](target-resume.md)

Fill out one row of evidence per proposed metric as the PRs merge. The goal is to answer an interview follow-up with the exact run, baseline, workload, and calculation.

## Claim-to-evidence map

| Claim | Sprints | Evidence to save | What the wording means |
| --- | --- | --- | --- |
| Product-read P95 from [A] to [B] at [R] RPS | 0–2 | Route name, fixed request trace, cache off/on, per-trial P95, outcome counts and achieved RPS | Compare identical endpoint behavior and payload. Use the same steady-state window and latency sample definition in both variants. |
| Highest passing load from [X] to [Y] RPS | 0–2 | Load ladder for each variant, frozen gate, three runs at the highest passing step and the first failing step | Use the same gate, trace and topology for both variants. Report achieved successful RPS, not offered load. |
| [D]% fewer MySQL reads | 1–2 | SQL digest counts or application query counters and successful response counts in the same window | Compute SQL reads per successful request in both runs. Specify catalog reads rather than database-wide reads unless those were actually measured. State whether stock is read live on a cache hit. |
| [N] indexed products and NDCG@10 from [Q0] to [Q1] | 3 | Index reconciliation, WANDS held-out queries and labels, share of unjudged top-10 results, scoring script, SQL baseline and Elasticsearch config | Use observed counts, report score changes on the 0–1 scale, and state the baseline. |
| Zero oversells across [N] attempts | 1 and 6 | Initial stock, accepted reservations, final database state, distinct users, concurrent attempt count, Hikari pool size, admin-edit race results and business rejections | Count attempts that reached the checkout path. Use “observed zero” and describe the test envelope. |
| Stripe acknowledgment P95 or zero missing accepted events | 4 | Signed fixtures, accepted unique event IDs, ack timestamps, injected provider delay, paid transitions and restart snapshots | Separate durable HTTP acknowledgment from order completion. Distinguish event deliveries, unique events and unique orders. |
| [U] unique events/sec at [L] s P95 | 5 | Outbox, publish and effect timestamps, unique committed effects, backlog over time and configuration | Use committed unique effects during steady state, with the same event time boundary for all trials. This is an event-pipeline result. |
| Zero duplicate simulated effects or [T] s recovery | 5 | Crash timing, producer/consumer dedup checks, expected and observed IDs, replay and backlog logs | Say which effect was simulated and time recovery from the same reference point used in the report. |
| [S]% checkout success during a [R]-RPS burst | 6 | Offered/achieved rates, accepted goodput, 429s, business rejections, unexpected failures and stock checks | Specify whether [R] is offered or delivered. Use checkout successes divided by offered checkout arrivals for the protected-traffic claim. |
| [G] successful RPS at [P] ms P95 on AWS | 7 | EC2 topology, benchmark manifest, success/latency thresholds, test duration, highest passing step and raw summaries | Use observed successful goodput from AWS staging, and identify the endpoint whose P95 is reported. |

## Calculations and terms

- **P95 reduction.** `100 × (baseline P95 − optimized P95) / baseline P95`. Summarize each variant using the same approach, such as the median of three valid per-trial P95s, and keep trial ranges.
- **SQL read reduction.** Apply the same relative-change formula to `catalog SQL reads / successful catalog responses`. Choose a benchmark window that attributes the counted statements to those routes.
- **Capacity.** Find the highest tested load where every repeated steady-state run passes the agreed thresholds. Report achieved goodput as successful requests divided by elapsed measurement seconds. Record timeout and 429 counts next to the result.
- **NDCG gain.** `new NDCG@10 − baseline NDCG@10` gives an absolute change on the 0–1 scale. Showing the two scores is usually easiest to read.
- **Concurrency.** Use “synthetic checkout attempts” for attempts, “k6 virtual users” for configured VUs, and “requests/sec” for measured traffic. A VU can issue multiple HTTP requests in a single journey.
- **Correctness.** Report an observed zero with the fixture size, failure cases and invariant. For example, report zero duplicate simulated confirmations in the replay matrix after reconciling every expected event ID.

## Evidence record template

Copy this record into the relevant sprint report after a benchmark or test.

| Field | Value |
| --- | --- |
| Proposed résumé bullet | |
| PR, commit and environment | |
| Baseline, optimized variant and comparable contract | |
| Fixture seed, dataset size and workload mix | |
| Scheduled rate, delivered rate, successful RPS and duration | |
| Measurement definition, trial count and spread | |
| Result and calculation | |
| Correctness checks, exceptions and known limits | |
| Command and raw artifact links | |

## Interview notes to keep with the evidence

- **Caching.** Trace one hit, one miss, an admin update, and an outage. Explain why that access distribution made the cache valuable and what happened to live inventory. Show the hit-ratio estimate for your TTL and load, and say where stock comes from on a hit.
- **Inventory.** Explain the admin-edit lost update, how the test reproduces it, and why a row lock, delta update or version check fixes it.
- **Search.** Walk through a query Elasticsearch improved and one it handled poorly. Explain the held-out split and how product changes become visible.
- **Payments and events.** Draw the inbox, order/outbox transaction, publisher and consumer commits. Pick one crash boundary and explain which state survives and how recovery works.
- **Traffic and cloud.** Show one passing steady-state point and one overload step. Explain the difference between scheduled load, successful requests, and intentional rejections, and identify the machine on which the results were measured.
