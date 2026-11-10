#!/usr/bin/env python3
"""Sprint 3 relevance evaluation: NDCG@10 on the WANDS queries for both search engines.

  python3 benchmarks/search_eval.py score --engine mysql --split tune
  python3 benchmarks/search_eval.py tune
  python3 benchmarks/search_eval.py score --engine elasticsearch --split heldout

Needs the bench stack with `up --search` and `seed-wands`. The same BENCH_PROJECT / BENCH_BACKEND_PORT variables as
bench.py select the stack.

score  Sends every query in the split to GET /product/search?engine=E&size=10 (the real endpoint, so the engine's
       code is what gets measured) and scores the top 10 against the WANDS labels.
tune   Tuning split only. Runs a fixed grid of Elasticsearch multi_match variants straight against the index with
       one _msearch per variant, and ranks them. Copy the winner into ElasticsearchSearchEngine, rebuild the
       backend, confirm with `score --split tune`, and only then score the held-out split.

Scoring. Gains are Exact 2, Partial 1, Irrelevant 0, with DCG = sum((2^gain - 1) / log2(rank + 1)). The ideal
DCG uses every labeled product of the query. Results without a label count as irrelevant for both engines, and
the share of unjudged top-10 results is reported next to the score, because a high share means the labels can't
tell the engines apart. A second score, judged-only NDCG@10, drops unjudged results before cutting at 10
(a "condensed list"), so an engine is neither rewarded nor punished for results nobody labeled. Report both.
A query with no results scores 0.
"""
from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

from bench import BACKEND_PORT, REPO_DIR, RESULTS_DIR, WANDS_DIR, compose, log, stamp, utc_now

K = 10
DEPTH = 100
EVAL_DIR = RESULTS_DIR / "search-eval"
INDEX = "products"

# The tuning grid. Field boosts, multi_match type and fuzziness are the usual first knobs; the analyzer
# (english, in the index mapping) stays fixed so the grid needs no reindexing.
VARIANTS = [
    {"name": f"{qtype}-name{boost}{'-fuzzy' if fuzzy else ''}", "type": qtype, "name_boost": boost, "fuzzy": fuzzy}
    for qtype in ("best_fields", "most_fields", "cross_fields")
    for boost in (1, 2, 3)
    for fuzzy in ((False, True) if qtype != "cross_fields" else (False,))
]


def load_queries(split: str) -> list[dict]:
    path = WANDS_DIR / "queries.json"
    if not path.exists():
        raise SystemExit("no WANDS query set; run `python3 benchmarks/bench.py seed-wands` first")
    queries = json.loads(path.read_text())
    return [q for q in queries if split == "all" or q["split"] == split]


def ndcg(ranked: list[int], labels: dict[str, int]) -> tuple[float, int]:
    """NDCG@K and the number of unjudged results in the top K."""
    dcg, unjudged = 0.0, 0
    for rank, product_id in enumerate(ranked[:K], start=1):
        gain = labels.get(str(product_id))
        if gain is None:
            unjudged += 1
            gain = 0
        dcg += (2 ** gain - 1) / math.log2(rank + 1)
    ideal = sorted(labels.values(), reverse=True)[:K]
    idcg = sum((2 ** g - 1) / math.log2(rank + 1) for rank, g in enumerate(ideal, start=1))
    return (dcg / idcg if idcg else 0.0), unjudged


def judged_ndcg(ranked: list[int], labels: dict[str, int]) -> float:
    """NDCG@K over the condensed list: unjudged results removed, so ranks close up over judged ones."""
    return ndcg([p for p in ranked if str(p) in labels], labels)[0]


def summarize(per_query: list[dict]) -> dict:
    scores = [q["ndcg"] for q in per_query]
    judged = [q["judged_ndcg"] for q in per_query]
    returned = sum(len(q["ranked"][:K]) for q in per_query)
    return {
        "queries": len(per_query),
        "ndcg_at_10": statistics.fmean(scores) if scores else 0.0,
        "judged_ndcg_at_10": statistics.fmean(judged) if judged else 0.0,
        "unjudged_share": (sum(q["unjudged"] for q in per_query) / returned) if returned else 0.0,
        "zero_result_queries": sum(1 for q in per_query if not q["ranked"]),
    }


def search_ids(engine: str, text: str, size: int = K) -> list[int]:
    params = urllib.parse.urlencode({"q": text, "engine": engine, "size": size})
    with urllib.request.urlopen(f"http://127.0.0.1:{BACKEND_PORT}/product/search?{params}", timeout=30) as resp:
        return [item["id"] for item in json.loads(resp.read())["items"]]


def cmd_score(args: argparse.Namespace) -> None:
    queries = load_queries(args.split)
    per_query, latencies = [], []
    for q in queries:
        started = time.perf_counter()
        # 100 results, so the judged-only score can fill its top 10 past unjudged results.
        ranked = search_ids(args.engine, q["query"], DEPTH)
        latencies.append((time.perf_counter() - started) * 1000)
        score, unjudged = ndcg(ranked, q["labels"])
        per_query.append({"query_id": q["query_id"], "query": q["query"], "ndcg": score, "unjudged": unjudged,
                          "judged_ndcg": judged_ndcg(ranked, q["labels"]), "ranked": ranked[:K]})
    summary = summarize(per_query) | {"engine": args.engine, "split": args.split,
                                      "median_ms_sequential": statistics.median(latencies)}
    EVAL_DIR.mkdir(parents=True, exist_ok=True)
    out = EVAL_DIR / f"{stamp()}_{args.engine}_{args.split}.json"
    out.write_text(json.dumps({"summary": summary, "finished_at": utc_now(), "per_query": per_query}, indent=1))
    print(json.dumps(summary, indent=2))
    log(f"per-query results: {out.relative_to(REPO_DIR)}")


def es_msearch(variant: dict, queries: list[dict]) -> list[list[int]]:
    """Runs the variant's query body for every query in one _msearch, inside the Elasticsearch container."""
    lines = []
    for q in queries:
        multi_match = {"query": q["query"], "type": variant["type"],
                       "fields": [f"name^{variant['name_boost']}", "description"]}
        if variant["fuzzy"]:
            multi_match["fuzziness"] = "AUTO"
        lines.append(json.dumps({"index": INDEX}))
        lines.append(json.dumps({
            "query": {"bool": {"must": [{"multi_match": multi_match}]}},
            "sort": [{"_score": "desc"}, {"id": "asc"}], "size": DEPTH, "_source": False, "track_total_hits": False}))
    body = "\n".join(lines) + "\n"
    out = compose("exec", "-T", "elasticsearch", "curl", "--silent", "--fail", "-H",
                  "Content-Type: application/x-ndjson", "--data-binary", "@-",
                  f"http://127.0.0.1:9200/{INDEX}/_msearch", capture=True, input_text=body).stdout
    responses = json.loads(out)["responses"]
    if any("error" in r for r in responses):
        raise SystemExit(f"variant {variant['name']} failed: {next(r['error'] for r in responses if 'error' in r)}")
    return [[int(hit["_id"]) for hit in r["hits"]["hits"]] for r in responses]


def cmd_tune(args: argparse.Namespace) -> None:
    queries = load_queries("tune")
    rows = []
    for variant in VARIANTS:
        per_query = []
        for q, ranked in zip(queries, es_msearch(variant, queries)):
            score, unjudged = ndcg(ranked, q["labels"])
            per_query.append({"ndcg": score, "unjudged": unjudged, "judged_ndcg": judged_ndcg(ranked, q["labels"]),
                              "ranked": ranked[:K]})
        rows.append(summarize(per_query) | {"variant": variant["name"]})
        log(f"{variant['name']:<28} NDCG@10 {rows[-1]['ndcg_at_10']:.4f}")
    rows.sort(key=lambda r: r["ndcg_at_10"], reverse=True)
    lines = [f"# Elasticsearch tuning grid ({len(queries)} tuning queries)", "",
             "| Variant | NDCG@10 | Judged-only NDCG@10 | Unjudged top-10 share | Zero-result queries |",
             "| --- | --- | --- | --- | --- |"]
    lines += [f"| {r['variant']} | {r['ndcg_at_10']:.4f} | {r['judged_ndcg_at_10']:.4f} | {r['unjudged_share']:.1%} "
              f"| {r['zero_result_queries']} |" for r in rows]
    EVAL_DIR.mkdir(parents=True, exist_ok=True)
    out = EVAL_DIR / f"{stamp()}_tune.md"
    out.write_text("\n".join(lines) + "\n")
    print("\n".join(lines))
    log(f"grid: {out.relative_to(REPO_DIR)}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    score = sub.add_parser("score", help="NDCG@10 of one engine through the backend's search endpoint")
    score.add_argument("--engine", choices=["mysql", "elasticsearch"], required=True)
    score.add_argument("--split", choices=["tune", "heldout", "all"], required=True)
    score.set_defaults(func=cmd_score)
    tune = sub.add_parser("tune", help="Elasticsearch query grid on the tuning split")
    tune.set_defaults(func=cmd_tune)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    sys.exit(main())
