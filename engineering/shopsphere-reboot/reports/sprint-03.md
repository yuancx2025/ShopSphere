# Sprint 3 report · Elasticsearch search

[Roadmap](../README.md) · [Sprint 3 plan](../sprints/03-elasticsearch-search.md) · [Sprint 2 report](sprint-02.md) · [Benchmarks](../../../benchmarks/README.md)

Status: implemented on `claude/sprint-3-kickoff-d65402`. That branch is built on the unmerged Sprint 2 branch (`claude/sprint-2-redis-cache-e29ad1` at `42b75aa`) plus the payment-deferral roadmap commits from `claude/sprint-zero-worksheet-review-c8c1d6`. It has not been split into the planned PRs 07 and 08 or merged.

## Headline findings

1. **Elasticsearch ranks WANDS much better than MySQL FULLTEXT.** On the 192 held-out queries, NDCG@10 went from **0.449 (MySQL FULLTEXT) to 0.707 (Elasticsearch)**, +0.258, with a 95% bootstrap interval of [+0.219, +0.298]. Elasticsearch did better on 149 queries and worse on 25. The plan's stretch target was +0.05.
2. **Part of that gap comes from labels, not ranking.** 35% of MySQL's top-10 results have no WANDS label, against 12% for Elasticsearch. Unlabeled results score as irrelevant, which inflates the gap. Dropping unlabeled results before cutting at 10 ("judged-only" NDCG, a condensed list) still gives **0.666 → 0.767**, +0.101 [+0.071, +0.132]. Quote that number when the label-coverage objection matters. Both measures clear the target.
3. **MySQL FULLTEXT favors long documents.** The median description in its top 20 results is 733 characters, against 332 for Elasticsearch. InnoDB's natural-language relevance grows with term counts and normalizes weakly for length. That also makes MySQL's search responses almost twice as large (21.3 KB against 11.5 KB per 20 results).
4. **Both engines pass the latency target easily.** At 40 req/s on the WANDS catalog (42,994 products), with the same 480-query trace, median p95 across 3 trials was **19.9 ms on Elasticsearch and 53.0 ms on MySQL**. The target is 300 ms. There were 0 failures and 0 dropped iterations across 6 valid trials.
5. **Edits become searchable in under a second.** The p95 time from a committed admin edit to the first search response that shows it was **0.91 s** (50 edits, 50 ms probe). The target is 5 s. During a 20 s Elasticsearch outage, 50 edits queued in MySQL. All 50 were searchable **1.29 s** after Elasticsearch resumed.
6. **Backfill is fast enough to skip a zero-downtime rebuild.** 10,001 products indexed in 2.1 s, 42,994 WANDS products in 5.0–10.9 s, and 100,001 in 9.6 s, with the document count checked against MySQL each time.

## Design decisions

| Decision | Choice | Why |
| --- | --- | --- |
| Source of truth | MySQL owns the catalog, stock and prices. Elasticsearch holds only searchable fields: id, name, description, category_id, price_cents and search_version. | Search can fail or lag without affecting checkout. An Elasticsearch outage returns 503 from `/product/search` only. |
| Response hydration | Elasticsearch returns ids. The cards (including stock) come from one MySQL primary-key `IN` query. | Both engines return identical JSON, and stock and displayed price are never stale. Cost: one indexed SQL read per search. Price *filters* still run on the indexed price, so they can lag an edit by the indexing delay. |
| Sync | `search_index_jobs` rows are written in the same transaction as admin create/update. A single scheduled worker (200 ms poll) reads the current row for each job, bulk-upserts it, then deletes the jobs. | A committed change always has a job, and a rolled-back one never does. The queue lives in MySQL, so it survives backend restarts and Elasticsearch outages. Stock adjustments don't create jobs, because stock isn't indexed. |
| Ordering guard | `products.search_version`, bumped on every admin edit, is sent as the Elasticsearch external version. A version conflict counts as done. | A replayed job, a crash between upsert and delete, a second backend, or a rebuild that read a row before an edit can never overwrite a newer document. The tests send a stale snapshot last and check that the document stays at version 2. |
| Rebuild | `POST /product/search/rebuild` (admin) deletes and recreates the index, then queues every product. Synchronized with the worker so a bulk request never auto-creates the index with a dynamic mapping. | At 4,000–10,000 docs/s a full rebuild takes seconds, so an alias swap isn't worth building yet. Search returns partial results while the backfill drains. |
| Engine switch | `search.engine` sets the default. `?engine=mysql\|elasticsearch` on a request overrides it. | The relevance evaluation and the load test compare both engines on the same running stack and data. `bench.py up --search` makes Elasticsearch the default for the UI. |
| Bounds | `q` 1–200 characters, `size` 1–100, `page * size + size ≤ 1000`, sort `relevance\|price_asc\|price_desc` with an id tie-break, no total count (`hasMore` from one extra row). | The same validated query object goes to both engines. A count would cost a second full `MATCH` on MySQL. |
| MySQL baseline | `MATCH(name, description) AGAINST (? IN NATURAL LANGUAGE MODE)` over a V7 FULLTEXT index, `ORDER BY score DESC, id`. Default InnoDB tokenizer and stopwords. Not tuned. | It's the plain baseline the plan asked for. Boolean mode, ngram parsing or a custom stopword list could narrow the gap. That is listed as a follow-up so the comparison isn't overstated. |
| Elasticsearch query | `multi_match`, `type: cross_fields`, fields `name^2` and `description`, `english` analyzer, OR operator. | Chosen from a 15-variant grid on the tuning split only (below), then frozen in `ElasticsearchSearchEngine` before the held-out split was scored. |
| Longer text (V8) | `name` VARCHAR(512), `description` TEXT. | 65% of WANDS descriptions exceed 255 characters (median 366, max 4,060). Truncation would hide most of the text both engines rank. |

## What was built, mapped to the plan

### PR 07 · `feat/product-search`

| Plan item | Implementation |
| --- | --- |
| `ProductSearchService`, document mapping, bounded endpoint | `search/ProductSearchService`, `SearchQuery`, `SearchSort`, `GET /product/search`. Mapping in `resources/search/products-index.json` (strict, one shard, no replicas). |
| MySQL FULLTEXT comparator | `search/MySqlSearchEngine`, Flyway `V7__Product_Search.sql`. |
| Elasticsearch engine | `search/ElasticsearchSearchEngine` (elasticsearch-java 8.10.4, Spring Boot's client auto-configuration). |
| Durable index jobs, retryable worker, backfill | `search/SearchIndexJobs`, `search/ProductIndexer`, `SearchConfig.IndexerSchedule`, `POST /product/search/rebuild`. Metrics: `search_index_lag` (histogram), `search_index_failures_total`, `search_index_documents_total`. |
| Compose profile with resource limits | `elasticsearch:8.10.4` in `benchmarks/docker-compose.bench.yml` (profile `search`): 2 CPU, 2 GB, 1 GB heap, security off on the internal network. `bench.py up --search`. `seed` rebuilds and verifies the index. |
| Merge gate tests | `ProductSearchTest` (Testcontainers MySQL + Elasticsearch, 8 tests): same filters and sort on both engines; stemming difference; bounds and 400s; backfill count; admin edit reaches search through the job table; replayed and stale writes never regress a document; jobs survive an Elasticsearch pause and a fresh indexer drains them (503 from Elasticsearch search, MySQL search unaffected); rebuild during edits converges. All 46 backend tests pass. |

### PR 08 · `feat/search-ui-evaluation`

| Plan item | Implementation |
| --- | --- |
| React search and filters | The navbar search box (previously inert) goes to `/search?q=…`. `pages/search/SearchPage.tsx` has category, min/max price and sort, previous/next paging up to the 1,000-result window, and 400/503 messages. All state lives in the URL. Checked in a browser against the bench stack: query, price filter, price sort, page 2, and the "minPrice must not exceed maxPrice" error. |
| Relevance dataset | `seed/load_wands.py` maps WANDS (MIT) into the catalog: id = WANDS id + 1, category = first `product_class` (598 categories), deterministic log-uniform prices of $5–$2,000. It splits the 480 queries 288 tuning / 192 held-out with seed 20261109, before any scoring. `bench.py seed-wands` loads and indexes it. |
| Graded evaluation | `benchmarks/search_eval.py score` (through the real endpoint) and `tune` (grid by `_msearch`). Gains are Exact 2, Partial 1, Irrelevant 0, with exponential DCG. Both standard NDCG@10 and judged-only NDCG@10 are reported, plus the unjudged share. |
| Latency comparison | `k6/product-search.js` and `bench.py run search --engine …`: the 480 WANDS queries in a deterministic hashed trace, size 20, relevance sort. |
| Indexing lag | `bench.py search-lag [--outage S]`: renames a product through the admin API, polls search every 50 ms until the new name finds it, then optionally pauses Elasticsearch and times the catch-up. It restores the names afterwards. |

## Evidence

All runs: local Docker stack `shopsphere-bench-s3`, backend 2 CPU / 1.5 GB, MySQL 2 CPU / 1 GB, Elasticsearch 2 CPU / 2 GB / 1 GB heap, k6 2 CPU, all on the same MacBook. Three idle bench stacks from earlier sprints were also running. Raw results are under `benchmarks/results/` (git-ignored). File names are listed so the results can be found again.

### Relevance (WANDS, NDCG@10)

Tuning split (288 queries), before freezing. Elasticsearch's starting point was `best_fields` with `name^2`.

| Engine / variant | NDCG@10 | Judged-only NDCG@10 | Unjudged top-10 share |
| --- | --- | --- | --- |
| MySQL FULLTEXT (baseline, not tuned) | 0.461 | 0.671 | 35.9% |
| Elasticsearch `best_fields`, `name^2` (initial) | 0.665 | 0.712 | 9.7% |
| Elasticsearch `cross_fields`, `name^2` (**chosen**) | 0.704 | 0.759 | 10.8% |
| Elasticsearch `cross_fields`, `name^3` | 0.703 | 0.745 | 9.0% |
| Elasticsearch `most_fields`, `name^2` | 0.689 | 0.737 | 9.3% |
| Elasticsearch `best_fields`, `name^1`, fuzzy | 0.588 (worst) | — | 27.2% |

Fuzziness (`AUTO`) lowered NDCG in every variant it was tried on, by 0.017–0.040. Its only visible upside was removing the one zero-result query. The full grid is in `search-eval/20261110T002233Z_tune.md`. As a check that the grid and the app run the same query, the app's own score before the change, through `/product/search`, was 0.6646, the same as the grid's `best_fields-name2` row. The frozen app reproduced 0.7042.

Held-out split (192 queries), scored once after freezing (`search-eval/20261110T002247Z_mysql_heldout.json`, `…002249Z_elasticsearch_heldout.json`):

| Engine | NDCG@10 | Judged-only NDCG@10 | Unjudged top-10 share | Zero-result queries |
| --- | --- | --- | --- | --- |
| MySQL FULLTEXT | **0.449** | 0.666 | 34.8% | 0 |
| Elasticsearch | **0.707** | 0.767 | 11.8% | 0 |
| Difference (paired, 10,000-sample bootstrap) | +0.258 [+0.219, +0.298] | +0.101 [+0.071, +0.132] | | |
| Per query: better / worse / tied | 149 / 25 / 18 | 111 / 40 / 41 | | |

Largest held-out regressions (NDCG@10, MySQL → Elasticsearch):

| Query | MySQL | Elasticsearch | Note |
| --- | --- | --- | --- |
| rack glass | 0.309 | 0.000 | All 10 Elasticsearch results are unlabeled, so this may be a labeling gap rather than a ranking loss. |
| almost heaven sauna | 0.532 | 0.248 | Brand phrase. `cross_fields` spreads the terms across fields instead of requiring the phrase. |
| sheets for twinxl | 0.780 | 0.513 | "twinxl" is one token in the query but "twin xl" in product text. A synonym or word-delimiter rule would help both engines. |
| emma headboard | 0.391 | 0.143 | Brand name with a common word. |
| king adjustable bed including mattress | 0.508 | 0.265 | Long query. Stemming matches "beds" and "mattresses" broadly. |

Largest gains: "chair pillow cushion" (0.16 → 1.00), "block coffee table" (0.10 → 1.00), "dining room tables" (0.06 → 0.91, from stemming "tables").

### Search latency at 40 req/s (target: p95 < 300 ms)

WANDS catalog, 480-query trace (trace seed 1), size 20, relevance sort. 30 s warm-up and 1 min measured per trial. The engines ran in alternating order (Elasticsearch first).

| Engine | Trials passing gate | p50 ms (per trial) | p95 ms (per trial) | Median p95 | p99 ms (per trial) | App SQL reads / success | Avg response bytes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Elasticsearch (`runs/20261110T003256Z_search_40rps_elasticsearch`) | 3/3 | 4.4 / 5.8 / 5.0 | 11.7 / 59.0 / 19.9 | **19.9** | 38.3 / 176.3 / 56.0 | 1.00 (hydration) | 11,474 |
| MySQL FULLTEXT (`runs/20261110T003735Z_search_40rps_mysql`) | 3/3 | 7.0 / 8.0 / 7.8 | 38.2 / 53.0 / 61.1 | **53.0** | 86.0 / 140.1 / 212.0 | 1.00 (the `MATCH` query) | 21,320 |

Two earlier runs are excluded and kept only for honesty. `20261110T002311Z_search_40rps_mysql` and `…002752Z_…elasticsearch` each failed trial 1: 5 and 17 dropped iterations on a JVM that had just started. Elasticsearch's trial 3 (48 drops, p95 302 ms) overlapped a full Maven test run with its own Testcontainers MySQL and Elasticsearch on the same host. One `docker stats` snapshot during an Elasticsearch-engine run showed the Elasticsearch container at about 150% CPU. No per-engine CPU comparison was recorded, so resource cost is still an open question.

### Index freshness and backfill

`search-lag/20261110T004343Z.json`, WANDS catalog, healthy stack:

| Measure | Result |
| --- | --- |
| Commit-to-searchable, 50 sequential admin edits (50 ms probe) | p50 0.79 s, p95 **0.91 s**, max 1.19 s |
| Elasticsearch paused 20 s while 50 edits committed | 50 jobs queued in MySQL; all 50 searchable **1.29 s** after resume; no lost edits |

The healthy lag is mostly Elasticsearch's 1 s refresh interval plus the 200 ms worker poll. The backend also exports `search_index_lag`, which covers commit to bulk acknowledgement and excludes the refresh.

| Backfill (`seed` / `seed-wands` with search on) | Documents | Time | Rate | Count matches MySQL |
| --- | --- | --- | --- | --- |
| Synthetic fixture v3 | 10,001 | 2.1 s | 4,827/s | yes |
| WANDS (first, cold JVM) | 42,994 | 10.9 s | 3,959/s | yes |
| WANDS (again, after the 100k run) | 42,994 | 5.0 s | 8,637/s | yes |
| Synthetic scale run | 100,001 | 9.6 s | 10,466/s | yes |

## Reproduce

```bash
cd backend && ./mvnw test
```

```bash
export BENCH_PROJECT=shopsphere-bench-s3 BENCH_BACKEND_PORT=18380 BENCH_PROMETHEUS_PORT=9390 BENCH_GRAFANA_PORT=3301
mkdir -p benchmarks/results/wands/raw
for f in product.csv query.csv label.csv; do curl -fL -o benchmarks/results/wands/raw/$f https://raw.githubusercontent.com/wayfair/WANDS/main/dataset/$f; done
python3 benchmarks/bench.py up --search
python3 benchmarks/bench.py seed --products 100000      # scale backfill check
python3 benchmarks/bench.py seed-wands
python3 benchmarks/search_eval.py tune
python3 benchmarks/search_eval.py score --engine mysql --split heldout
python3 benchmarks/search_eval.py score --engine elasticsearch --split heldout
python3 benchmarks/bench.py run search --engine elasticsearch --rate 40 --trials 3 --warmup 30s --duration 1m
python3 benchmarks/bench.py run search --engine mysql --rate 40 --trials 3 --warmup 30s --duration 1m
python3 benchmarks/bench.py search-lag --samples 50 --outage 20
```

## Known limits and follow-ups

- **The held-out split is now used.** Any further tuning, such as synonyms or phrase boosts, needs a fresh split or a new query set. Re-scoring the same 192 queries after more tuning would leak.
- **MySQL wasn't tuned.** Boolean mode with required terms, the ngram parser or a smaller stopword list are fair next steps for the baseline before claiming the gap is all Elasticsearch.
- **Price sort over OR matches.** Sorting "coffee table" by price lists cheap products that match only "table" first. Requiring all terms (or `minimum_should_match`) when the sort isn't relevance would fix it in both engines.
- **Category text isn't searchable.** WANDS `product_class` would likely add relevance in both engines. It was left out to keep both on the same two fields.
- **Single worker per backend.** Two backends would both drain the same jobs. The version guard keeps that correct, but it doubles the bulk work. A `SKIP LOCKED` claim fixes that if more instances appear.
- **Quiet-host rerun.** Latency comes from a laptop with other stacks running and the 30 s + 1 min protocol. Rerun on the full 2 + 5 min protocol before quoting the latency numbers. The relevance and freshness results don't depend on host noise.
- **Not covered:** facets and typo tolerance. Fuzziness was measured and rejected. Search results aren't cached.

## Résumé wording supported so far

- "Built Elasticsearch product search with full-text ranking and category/price filters across 42,994 products, improving held-out NDCG@10 from 0.45 to 0.71 over MySQL FULLTEXT on the WANDS benchmark (192 held-out queries)." Add "(0.67 → 0.77 counting judged results only)" if asked about label coverage.
- "Built a transactional MySQL-to-Elasticsearch sync with versioned upserts, making catalog edits searchable within 0.9 s at p95 and recovering a 20 s search outage with no lost updates."
- "Served search at 20 ms p95 at 40 req/s" only with the laptop and shortened-protocol caveat until a quiet-host rerun.
