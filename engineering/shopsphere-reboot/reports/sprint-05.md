# Sprint 5 report · RocketMQ and asynchronous workers

[Roadmap](../README.md) · [Sprint 5 plan](../sprints/05-rocketmq-workers.md) · [Sprint 3 report](sprint-03.md) · [Benchmarks](../../../benchmarks/README.md)

Status: implemented on `claude/sprint-5-readiness-979040` (outbox, publisher, consumer groups, tests) and measured on `claude/sprint-5-measurements`, which adds the API/worker split, the pipeline benchmark and the replay command. Both are stacked on the unmerged Sprint 3 branch and have not been split into the planned PRs 11 and 12 or merged.

## Headline findings

1. **No event was lost and none applied twice.** Across 11 measured runs, 174,954 outbox events were reconciled against both consumer groups' ledgers: 0 duplicate effects, and 0 missing (in the Elasticsearch-outage run, after replaying the dead-lettered events). That covers 3 healthy and 1 noisy 50/s trial, a 100–400/s ladder, a worker SIGKILL, a backend SIGKILL, a 30 s worker stop and a 60 s Elasticsearch outage. Every edited product's search document version matched `products.search_version` once the backlog drained (except in the outage run, until replay).
2. **50 events/s meets the target with room to spare.** In the three trials without a host stall, p95 outbox-to-effect lag was **0.11–0.31 s** for the search indexer and 0.11–0.22 s for the cache invalidator. The target is 2 s. Backlog stayed in single digits. One trial overlapped a host-wide stall and reached 2.10 s p95 on the search indexer (details below).
3. **Highest stable step: 100 events/s. The search indexer is the bottleneck.** At 100/s p95 lag was 0.49 s with a flat backlog. At 200/s the backlog held for about 80 s, then a stall pushed the search indexer behind (p95 19 s). At 400/s it completed about 235 events/s while input continued, then drained at about 640/s once input stopped. `ProductIndexer.indexNow` is `synchronized` and sends one document per bulk request, so the indexer's four consume threads run one at a time. It also shares MySQL's 2 CPUs with the admin writes and the ledger inserts. The broker never limited throughput.
4. **Redelivery is real and the ledger absorbs it.** A worker SIGKILL redelivered 8 already-applied events per group. Re-sending all 9,577 events of a run delivered 19,154 duplicates across the two groups. That is the state a publisher crash between send and mark leaves behind, and every duplicate was skipped: 0 new effects.
5. **A 30 s worker stop recovers in 41 s.** The backlog peaked at 2,322 events and was back to its pre-fault level 41 s after the worker was started, including JVM boot. That is 74 s after the stop began. The target is 120 s.
6. **Dead-lettering and replay work end to end.** With one redelivery allowed, a 60 s Elasticsearch outage at 20 events/s sent **1,223** search-indexer events to `%DLQ%search-indexer`, exactly the number reconciliation reported missing. It also left 481 search documents stale. After Elasticsearch came back, `bench.py events replay` re-published those 1,223 events under their original IDs. The search indexer applied all of them in 4.1 s, the cache invalidator skipped them as duplicates, and 0 documents stayed stale.

## What was built, mapped to the plan

### PR 11 · `feat/async-catalog-workers` (already on `claude/sprint-5-readiness-979040`)

| Plan item | Implementation |
| --- | --- |
| Broker and pinned client | `apache/rocketmq:5.3.2` name server and broker in the bench Compose `events` profile with explicit heaps; `rocketmq-client` 5.3.2 (classic remoting). Topic created explicitly; auto-create off. |
| Outbox with stable IDs | Flyway V9 turns Sprint 3's `search_index_jobs` into the outbox: `change_type` (`CREATED`, `UPDATED`, `STOCK`) and `published_at`. Admin edits and stock adjustments write a row in the same transaction. The row id is the event ID and message key. |
| Publisher | `OutboxPublisher`: polls every 100 ms, batches of 200, synchronous send with a 3 s bound and 2 retries, marks `published_at` only after `SEND_OK`; a failed send leaves the rest pending in order. |
| Two consumer groups | `search-indexer` (versioned Elasticsearch upsert of the current row) and `cache-invalidator` (evict the product's detail and every catalog page: the delayed second eviction for Sprint 2's stale-refill race). |
| Dedup with the effect | `EffectLedger.applyOnce`: `INSERT IGNORE` into `consumed_events (consumer_group, event_id)` and the effect in one MySQL transaction, before acknowledging. |
| Retry and dead letter | `RECONSUME_LATER` on failure; `events.consumer.max-reconsume-times` (default 5) before the broker moves the message to `%DLQ%<group>`. |
| Merge-gate tests | `CatalogEventsTest`: one edit through a real broker updates the search document to version 2 and evicts a stale cache entry with one ledger row per group; a duplicate delivery is skipped by both; a paused broker leaves the row pending. All 48 backend tests pass. |

### PR 12 · `test/messaging-reliability` (this branch)

| Plan item | Implementation |
| --- | --- |
| Worker you can stop | `events.publisher.enabled` / `events.consumer.enabled` (both default true). `bench.py up --events` runs the backend as the API and publisher only, and a `worker` container (same image, 1 CPU / 1 GB) with only the consumer groups. |
| Workload | `k6/catalog-edits.js`: constant arrival rate of admin writes, alternating a re-save of the product's own fields (`UPDATED`, bumps `search_version`) and a +1 stock adjustment (`STOCK`), on products hashed from the iteration number. The admin token is signed in before the run. |
| Telemetry and reconciliation | `bench.py events run`: per-second outbox, unpublished and per-group ledger counts; after the drain, every outbox ID in the window is matched against each group's ledger (missing, duplicate, orphan rows). Lag is `consumed_at − event_created_at`, both MySQL timestamps. Each edited product's Elasticsearch `_version` is checked against `search_version`, and the dead-letter topics are counted with `mqadmin`. The worker's effect, duplicate and failure counters are summed across restarts. |
| Faults | `--fault kill-worker\|kill-backend` (SIGKILL, then start), `stop-worker\|stop-es` (down for `--fault-for` seconds). The result adds peak backlog and the time back to the pre-fault backlog. |
| Replay | `bench.py events replay RUN [--group G]` clears `published_at` for the run's events a group never applied, so the publisher re-sends them with the same IDs. `--all` re-sends every event in the run to exercise the producer crash window. |

## Evidence

All runs: local Docker stack `shopsphere-bench-s5` on a 10-core MacBook (Docker VM 10 CPUs / 7.6 GB), synthetic fixture with 10,001 products. Limits: backend 2 CPU / 1.5 GB, worker 1 CPU / 1 GB, MySQL 2 CPU / 1 GB, Redis 1 CPU / 256 MB, Elasticsearch 2 CPU / 2 GB, broker 1 CPU / 1 GB, name server 0.5 CPU, k6 2 CPU, all on the same host (host load average 5–7 during the runs). Code: `223d3f0` plus this branch's then-uncommitted changes. Raw results are in `benchmarks/results/events/` (git-ignored); run names are listed.

Lag runs from the outbox row's commit to the consumer's ledger row and effect committing. Throughput counts unique committed effects.

### Steady state at 50 events/s (target: p95 lag < 2 s, stable backlog)

1 min warm-up, 5 min measured, about 15,000 events per trial.

| Run | Events | Search indexer lag p50 / p95 / p99 / max (s) | Cache invalidator lag p50 / p95 / p99 / max (s) | Backlog | Missing | Edits: p95, failed, dropped |
| --- | ---: | --- | --- | --- | ---: | --- |
| `20261110T041956Z_50eps` | 14,998 | 0.07 / **0.17** / 0.52 / 0.91 | 0.07 / **0.15** / 0.33 / 10.82 | 1–25 | 0 | 31 ms, 0%, 3 |
| `20261110T042624Z_50eps` | 14,872 | 0.11 / **2.10** / 3.68 / 6.07 | 0.10 / **1.50** / 3.36 / 10.98 | up to 207 | 0 | 1,110 ms, 1.52%, 128 |
| `20261110T043247Z_50eps` | 14,967 | 0.07 / **0.31** / 1.14 / 1.83 | 0.07 / **0.22** / 1.01 / 1.73 | 1–14 | 0 | 189 ms, 0%, 34 |
| `20261110T050115Z_50eps` | 14,961 | 0.06 / **0.11** / 0.33 / 2.39 | 0.06 / **0.11** / 0.19 / 2.39 | single digits | 0 | 11 ms, 0.21%, 40 |

- **3 of 4 trials pass with a wide margin.** The second trial ran into a host-wide stall. The admin edits themselves slowed to 1.1 s p95 with 1.5% failures and 128 dropped iterations, and the outbox briefly held 134 unpublished rows, so the backend and MySQL stalled too, not only the consumers. Its search-indexer p95 of 2.10 s narrowly misses the target. Every trial reconciled with 0 missing.
- **The ~11 s cache-invalidator maximums are retries.** A Redis command hit the 100 ms timeout during a stall (1 failure in trial 1, 5 in trial 2). The broker redelivered the event after its 10 s first-retry delay, and it succeeded then.
- The k6 generator dropped a few iterations in most runs (3–128 of ~15,000) during host stalls. The achieved input rate was 49.6–50.0/s in every trial.

### Rate ladder (find where lag stops being stable)

30 s warm-up, 2 min measured, one trial per step.

| Run | Input rate | Search indexer: completion, lag p95 | Cache invalidator: completion, lag p95 | Backlog | Missing | Verdict |
| --- | ---: | --- | --- | --- | ---: | --- |
| `20261110T043904Z_100eps` | 99.0/s | 98.9/s, **0.49 s** | 98.9/s, 0.32 s | flat, ≤ 14 | 0 | stable |
| `20261110T044150Z_200eps` | 195.3/s | 176/s, **19.2 s** | 193/s, 4.3 s | flat for ~80 s, then grew to 2,964 (search indexer) | 0 | unstable |
| `20261110T044452Z_400eps` | 395.3/s | 307/s overall, **50.7 s** | 376/s, 9.3 s | search indexer grew ~160/s to 19,490 | 0 | unstable |

The highest stable step was **100 events/s**, twice the plan's 50/s goal. The first group to fall behind was always the search indexer. At 400/s it completed about 235/s while input continued, then drained the last 17,000 events at about 640/s once the admin writes stopped. The broker kept up throughout: unpublished outbox rows stayed under 340. Two causes are likely: the indexer serializes its threads on a `synchronized` single-document upsert, and the admin writes, ledger inserts and publisher marks all share MySQL's 2 CPUs. A batched upsert in the indexer is the obvious next step if a higher rate matters. The admin-edit p95 also passed 300 ms at 100/s and above, so these runs stress the whole laptop, not just the pipeline.

### Reconciliation with crash windows (target: 0 missing, 0 duplicate effects across 10,000 IDs)

| Run | Fault | Events | Missing | Duplicate effects | Redeliveries skipped by the ledger | Search versions mismatched |
| --- | --- | ---: | ---: | ---: | --- | ---: |
| `20261110T044923Z_50eps_kill-worker` | SIGKILL worker at 60 s, restarted at once | 9,984 | 0 | 0 | 8 per group (applied, offset not yet committed) | 0 of 3,947 |
| `20261110T045328Z_50eps_kill-backend` | SIGKILL backend (publisher) at 60 s, restarted at once | 9,577 | 0 | 0 | 0 (the kill did not land between send and mark) | 0 of 3,824 |
| same run, `resend.json` | every one of its 9,577 events re-sent with `replay --all` (the producer crash window, forced) | 9,577 × 2 groups | 0 | **0** | **19,154** (9,577 per group), 0 new effects, drained in 5.3 s | 0 of 3,824 |

- **Consumer side.** The worker died after committing effects whose offsets the push consumer had not yet committed (it persists offsets every few seconds). The restarted worker received those events again, found their ledger rows, and skipped them. The backlog peaked at 847 and was back to its pre-fault level 28 s after restart, mostly JVM boot. Lag p95 over the whole run was 13.5 s because of the outage window.
- **Producer side.** Killing the backend left committed rows unpublished until it came back. They were published then, with a maximum lag of 9.5 s, and none were lost. Edits sent while the backend was down failed (3.9% of requests) and never committed, so they never became events. Because a single kill can miss the narrow window between send and mark, `replay --all` creates that state on purpose for a whole run.

### Worker restart recovery (target: back to the pre-fault backlog within 120 s)

`20261110T045732Z_50eps_stop-worker`: 50 events/s throughout, worker stopped at 60 s and started at 93 s (30 s down plus the stop and start commands).

| Measure | Result |
| --- | --- |
| Pre-fault backlog (max over 15 s) | 7 events |
| Peak backlog | 2,322 events (search indexer) |
| Back to ≤ 7 events after the worker was started | **40.8 s**, including worker JVM boot (74.0 s after the stop began) |
| Peak drain rate | 299/s (search indexer), 447/s (cache invalidator) |
| Missing / duplicate effects | 0 / 0 (8 redeliveries skipped after the graceful stop) |
| Unpublished outbox rows during the stop | ≤ 40: the publisher kept sending while no consumer ran |

### Dead letters and replay (target: track dead-letter items and verify replay after fixing the cause)

`20261110T050858Z_20eps_stop-es`: worker started with `BENCH_MAX_RECONSUME=1` (one redelivery, 10 s later). 20 events/s for 3 min, Elasticsearch stopped at 30 s and started again at 92 s.

| Step | Search indexer | Cache invalidator |
| --- | --- | --- |
| After the run (240 s drain timeout) | 2,378 applied, **1,223 missing**, 2,647 failed attempts | 3,601 applied, 0 missing, lag p95 0.11 s |
| `%DLQ%<group>` message count | **1,223** | 0 |
| Search documents behind `search_version` | 481 of 1,640 edited products | — |
| `events replay … --group search-indexer` after Elasticsearch recovered | 1,223 re-published under their IDs, all applied, drained in 4.1 s | 1,223 skipped as duplicates, 0 effects |
| After replay | **0 missing, 0 stale documents** | 0 missing |

The dead-letter count equals the missing count, so reconciliation found exactly the messages the broker gave up on. The replay goes through the outbox, not the DLQ topic. The outbox row is the source of truth for an event, and clearing `published_at` re-sends the original ID, so the cache invalidator can skip it. The DLQ messages themselves stay in the topic as the audit trail.

## Reproduce

```bash
cd backend && ./mvnw clean test
```

```bash
export BENCH_PROJECT=shopsphere-bench-s5 BENCH_BACKEND_PORT=18580 BENCH_PROMETHEUS_PORT=9590 BENCH_GRAFANA_PORT=3501
python3 benchmarks/bench.py up --events
python3 benchmarks/bench.py seed
python3 benchmarks/bench.py events run --rate 50 --warmup 1m --duration 5m          # x3
for r in 100 200 400; do python3 benchmarks/bench.py events run --rate $r --warmup 30s --duration 2m --drain-timeout 600; done
python3 benchmarks/bench.py events run --rate 50 --warmup 30s --duration 200s --fault kill-worker --fault-at 60
python3 benchmarks/bench.py events run --rate 50 --warmup 30s --duration 200s --fault kill-backend --fault-at 60
python3 benchmarks/bench.py events replay <kill-backend run> --all
python3 benchmarks/bench.py events run --rate 50 --warmup 30s --duration 3m --fault stop-worker --fault-at 60 --fault-for 30
BENCH_MAX_RECONSUME=1 python3 benchmarks/bench.py up --events
BENCH_MAX_RECONSUME=1 python3 benchmarks/bench.py events run --rate 20 --warmup 30s --duration 3m --fault stop-es --fault-at 30 --fault-for 60 --drain-timeout 240
BENCH_MAX_RECONSUME=1 python3 benchmarks/bench.py events replay <stop-es run> --group search-indexer
python3 benchmarks/bench.py up --events      # back to 5 redeliveries
```

## Known limits and follow-ups

- **Search indexer throughput.** A `synchronized` single-document upsert per event caps the indexer near 100–200 events/s on this host. Batching the consumer (`consumeMessageBatchMaxSize`) into one bulk request, or dropping the lock in favor of the version guard, would raise it. It isn't needed for the 50/s goal.
- **Noisy host.** One of four 50/s trials hit a host-wide stall, and the ladder steps are single trials. Rerun on a quieter host before quoting the 100/s ceiling or the lag tail. The reconciliation results don't depend on host noise.
- **Replay is outbox-based.** It assumes the outbox still holds the rows (they are kept with events on). A retention job for `search_index_jobs` and `consumed_events` would need to keep enough history for replay.
- **True poison messages** (an event that can never succeed, such as an unknown schema version) also land in the DLQ after the retries, but replay can't fix them until the code changes. That case was not run.
- **Protocol.** The 50/s trials used 1 + 5 min; the ladder and fault runs were shorter. The outbox-to-effect lag includes MySQL commit latency on both ends.

## Résumé wording supported so far

- "Built a RocketMQ catalog-event pipeline with a MySQL transactional outbox and idempotent Elasticsearch and Redis workers, processing 50 unique events/sec at 0.1–0.3 s p95 outbox-to-effect lag, and 100/sec stable, in synthetic tests on a laptop."
- "Validated retry, dead-letter and replay recovery across ~175,000 synthetic catalog events, with zero missing or duplicate effects after injected worker and publisher crashes, 19,154 forced redeliveries and a 60 s search outage (1,223 dead-lettered events replayed under their original IDs)."
- "Recovered a 30 s worker outage (2,322-event backlog) in 41 s after restart."
