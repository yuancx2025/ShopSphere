#!/usr/bin/env python3
"""ShopSphere benchmark runner (Sprints 0-5). Standard library only.

  python3 benchmarks/bench.py up [--observability] [--cache redis] [--search] [--events]   build and start the stack
  python3 benchmarks/bench.py seed [--products 10000]  load the deterministic fixture
  python3 benchmarks/bench.py run detail --rate 40 --pattern hot --trials 3
  python3 benchmarks/bench.py run page --rate 40 --sort price_asc
  python3 benchmarks/bench.py run search --rate 40 --engine elasticsearch   (after seed-wands)
  python3 benchmarks/bench.py ladder detail --rates 40,200,500,1000,2000
  python3 benchmarks/bench.py burst --users 100 --stock 20 --trials 3   checkout contention + invariants
  python3 benchmarks/bench.py explain                  catalog query plans and rows examined
  python3 benchmarks/bench.py compare RUN_A RUN_B      side-by-side summary of two runs
  python3 benchmarks/bench.py cache stats|flush|stop|start|pause|unpause   Redis cache and outages (Sprint 2)
  python3 benchmarks/bench.py smoke                    short end-to-end check (used by CI)
  python3 benchmarks/bench.py events run --rate 50 [--fault stop-worker]   event pipeline load + reconcile (Sprint 5)
  python3 benchmarks/bench.py events replay RUN        re-publish a run's unapplied events under their IDs
  python3 benchmarks/bench.py down [--volumes]

BENCH_PROJECT (default shopsphere-bench) and BENCH_BACKEND_PORT / BENCH_PROMETHEUS_PORT /
BENCH_GRAFANA_PORT let each worktree run its own stack and database volume.

Each trial is a warm-up k6 run followed by a measured k6 run. MySQL statement
digests are snapshotted around the measured run only, so SQL reads are
attributed to the measured window. Results land in benchmarks/results/.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import platform
import re
import shlex
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

BENCH_DIR = Path(__file__).resolve().parent
REPO_DIR = BENCH_DIR.parent
COMPOSE_FILE = BENCH_DIR / "docker-compose.bench.yml"
PROJECT = os.environ.get("BENCH_PROJECT", "shopsphere-bench")
NETWORK = f"{PROJECT}_default"
BACKEND_PORT = os.environ.get("BENCH_BACKEND_PORT", "18080")
K6_IMAGE = "grafana/k6:1.3.0"
MYSQL_IMAGE = "mysql:8.0.43"
BACKEND_IMAGE = os.environ.get(
    "BENCH_BACKEND_IMAGE", "shopsphere-backend:bench" if PROJECT == "shopsphere-bench" else f"shopsphere-backend:{PROJECT}")
os.environ.setdefault("BENCH_BACKEND_IMAGE", BACKEND_IMAGE)
DATABASE = "shopsphere_bench"
# Sprint 5: must match the broker image in docker-compose.bench.yml and events.topic in the backend.
ROCKETMQ_IMAGE = "apache/rocketmq:5.3.2"
EVENT_TOPIC = "catalog-product-changed"
MYSQL_ROOT_PASSWORD = "bench-root"
RESULTS_DIR = BENCH_DIR / "results"
FIXTURE_DIR = RESULTS_DIR / "fixture"
GENERATOR = BENCH_DIR / "seed" / "generate_fixture.py"
WANDS_LOADER = BENCH_DIR / "seed" / "load_wands.py"
WANDS_DIR = RESULTS_DIR / "wands"
WANDS_URL = "https://raw.githubusercontent.com/wayfair/WANDS/main/dataset"

APP_TABLES = "products|categories|users|cart|orders|orderitems|wishlist|processed_stripe_events"
APP_READ_RE = re.compile(rf"^SELECT\b.*?\bFROM\s+`?({APP_TABLES})`?(\s|$)", re.IGNORECASE | re.DOTALL)

SCENARIOS = {
    "detail": {"script": "product-detail.js", "route": "product_detail", "rate": 40, "cache": "productDetail"},
    "list": {"script": "product-list.js", "route": "product_list", "rate": 2},
    "cart": {"script": "checkout-skeleton.js", "route": "cart_read", "rate": 20, "tokens": True},
    "page": {"script": "product-page.js", "route": "product_page", "rate": 40, "cache": "catalogPage"},
    "search": {"script": "product-search.js", "route": "product_search", "rate": 40, "queries": True},
}

LIMIT_DEFAULTS = {
    "BENCH_BACKEND_CPUS": "2",
    "BENCH_BACKEND_MEM": "1536m",
    "BENCH_MYSQL_CPUS": "2",
    "BENCH_MYSQL_MEM": "1g",
    "BENCH_K6_CPUS": "2",
    "BENCH_REDIS_CPUS": "1",
    "BENCH_REDIS_MEM": "256m",
    "BENCH_ES_CPUS": "2",
    "BENCH_ES_MEM": "2g",
    "BENCH_ES_HEAP": "1g",
}
# Test-only admin from Flyway V2, used to trigger the search index rebuild after seeding.
ADMIN_EMAIL = "admin@shopsphere.com"
ADMIN_PASSWORD = "admin123"


# --------------------------------------------------------------------------- helpers

def log(message: str) -> None:
    print(f"[bench] {message}", flush=True)


def sh(cmd: list[str], *, check: bool = True, capture: bool = False, stdin=None,
       input_text: str | None = None) -> subprocess.CompletedProcess:
    if input_text is not None:
        return subprocess.run(cmd, check=check, text=True, input=input_text,
                              stdout=subprocess.PIPE if capture else None)
    return subprocess.run(cmd, check=check, text=True, stdin=stdin,
                          stdout=subprocess.PIPE if capture else None)


def compose(*args: str, observability: bool = False, **kwargs) -> subprocess.CompletedProcess:
    # The search and events profiles are always on: `up` names its services explicitly, so Elasticsearch and
    # RocketMQ only start with --search / --events, while exec/down can still reach them.
    cmd = ["docker", "compose", "-p", PROJECT, "-f", str(COMPOSE_FILE), "--profile", "search", "--profile", "events"]
    if observability:
        cmd += ["--profile", "observability"]
    return sh(cmd + list(args), **kwargs)


def mysql(sql: str, *, database: str | None = None) -> list[list[str]]:
    """Runs SQL as root inside the bench MySQL container; returns tab-split rows."""
    cmd = ["exec", "-T", "-e", f"MYSQL_PWD={MYSQL_ROOT_PASSWORD}", "mysql", "mysql", "-uroot", "-B", "-N"]
    if database:
        cmd += ["-D", database]
    out = compose(*cmd, "-e", sql, capture=True).stdout
    return [line.split("\t") for line in out.splitlines() if line]


def limits() -> dict[str, str]:
    return {key: os.environ.get(key, default) for key, default in LIMIT_DEFAULTS.items()}


def duration_seconds(value: str) -> float:
    total = 0.0
    parts = re.findall(r"(\d+(?:\.\d+)?)(ms|h|m|s)", value)
    if not parts or "".join(n + u for n, u in parts) != value:
        raise SystemExit(f"cannot parse duration {value!r}; use k6 syntax like 30s, 5m or 1m30s")
    for number, unit in parts:
        total += float(number) * {"ms": 0.001, "s": 1, "m": 60, "h": 3600}[unit]
    return total


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def stamp() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def load_fixture_manifest() -> dict:
    path = FIXTURE_DIR / "manifest.json"
    if not path.exists():
        raise SystemExit("no fixture manifest; run `python3 benchmarks/bench.py seed` first")
    manifest = json.loads(path.read_text())
    rows = mysql("SELECT COUNT(*) FROM products", database=DATABASE)
    if int(rows[0][0]) != manifest["total_products"]:
        raise SystemExit(
            f"database has {rows[0][0]} products but the fixture manifest expects "
            f"{manifest['total_products']}; re-run `bench.py seed` with the intended size")
    return manifest


# --------------------------------------------------------------------------- environment record

def git_info() -> dict:
    def git(*args: str) -> str:
        return sh(["git", "-C", str(REPO_DIR), *args], capture=True, check=False).stdout.strip()
    return {
        "commit": git("rev-parse", "HEAD"),
        "branch": git("rev-parse", "--abbrev-ref", "HEAD"),
        "dirty": bool(git("status", "--porcelain")),
    }


def host_info() -> dict:
    info = {"platform": platform.platform(), "machine": platform.machine()}
    if sys.platform == "darwin":
        info["cpu"] = sh(["sysctl", "-n", "machdep.cpu.brand_string"], capture=True, check=False).stdout.strip()
    docker = sh(["docker", "info", "--format", "{{.ServerVersion}}|{{.NCPU}}|{{.MemTotal}}"],
                capture=True, check=False).stdout.strip().split("|")
    if len(docker) == 3:
        info["docker"] = {"server": docker[0], "cpus": int(docker[1]), "memory_bytes": int(docker[2])}
    return info


def image_ids() -> dict:
    ids = {}
    for image in (BACKEND_IMAGE, MYSQL_IMAGE, K6_IMAGE):
        out = sh(["docker", "image", "inspect", "--format", "{{.Id}}", image], capture=True, check=False)
        ids[image] = out.stdout.strip() or None
    return ids


# --------------------------------------------------------------------------- SQL digests

def digest_snapshot() -> dict[str, dict]:
    rows = mysql(
        # The table can hold several rows for one (schema, digest), some with zero counts, so sum them.
        # Keying the raw rows by digest let a zero row overwrite the real one (a detail run reported 0 SQL reads).
        "SELECT DIGEST, SUM(COUNT_STAR), SUM(SUM_ROWS_EXAMINED), SUM(SUM_ROWS_SENT), SUM(SUM_TIMER_WAIT), "
        "ANY_VALUE(DIGEST_TEXT) FROM performance_schema.events_statements_summary_by_digest "
        f"WHERE SCHEMA_NAME = '{DATABASE}' AND DIGEST IS NOT NULL GROUP BY DIGEST")
    snapshot = {}
    for digest, count, examined, sent, timer, text in rows:
        snapshot[digest] = {"count": int(count), "rows_examined": int(examined), "rows_sent": int(sent),
                            "timer_ps": int(timer), "text": text.replace("\\n", " ").replace("\\t", " ")}
    return snapshot


def digest_delta(before: dict, after: dict) -> dict:
    statements = []
    for digest, now in after.items():
        prev = before.get(digest, {"count": 0, "rows_examined": 0, "rows_sent": 0, "timer_ps": 0})
        count = now["count"] - prev["count"]
        if count <= 0:
            continue
        statements.append({
            "count": count,
            "rows_examined": now["rows_examined"] - prev["rows_examined"],
            "rows_sent": now["rows_sent"] - prev["rows_sent"],
            "timer_ps": now["timer_ps"] - prev["timer_ps"],
            "app_table_read": bool(APP_READ_RE.match(now["text"])),
            "text": now["text"],
        })
    statements.sort(key=lambda s: s["count"], reverse=True)
    return {
        "all_statements": sum(s["count"] for s in statements),
        "all_selects": sum(s["count"] for s in statements if s["text"].upper().startswith("SELECT")),
        "app_table_reads": sum(s["count"] for s in statements if s["app_table_read"]),
        "app_rows_examined": sum(s["rows_examined"] for s in statements if s["app_table_read"]),
        "statements": statements,
    }


# --------------------------------------------------------------------------- Sprint 2: Redis cache

PROM_LINE_RE = re.compile(r'^(\w+)\{(.*)\}\s+(\S+)$')
PROM_LABEL_RE = re.compile(r'(\w+)="([^"]*)"')


def backend_cache_config() -> dict:
    """What the running backend was started with, read from its container rather than from this shell."""
    out = compose("exec", "-T", "backend", "printenv", "SPRING_CACHE_TYPE", "CATALOG_CACHE_TTL",
                  capture=True, check=False).stdout.split()
    return {"type": out[0] if out else None, "ttl": out[1] if len(out) > 1 else None}


def cache_snapshot() -> dict[str, dict[str, float]]:
    """Per-cache hit, miss and Redis error counters from the backend's internal Prometheus endpoint."""
    out = compose("exec", "-T", "backend", "curl", "--silent", "--fail",
                  "http://127.0.0.1:8081/actuator/prometheus", capture=True, check=False).stdout
    caches: dict[str, dict[str, float]] = {}
    for line in out.splitlines():
        match = PROM_LINE_RE.match(line)
        fields = {"catalog_cache_errors_total": "errors", "catalog_cache_bypassed_total": "bypassed"}
        if not match or match.group(1) not in ("cache_gets_total", *fields):
            continue
        labels = dict(PROM_LABEL_RE.findall(match.group(2)))
        name = labels.get("cache", "?")
        if match.group(1) == "cache_gets_total":
            field = labels.get("result")
            if field not in ("hit", "miss"):
                continue
        else:
            field = fields[match.group(1)]
        entry = caches.setdefault(name, {"hit": 0.0, "miss": 0.0, "errors": 0.0, "bypassed": 0.0})
        entry[field] += float(match.group(3))
    return caches


def cache_delta(before: dict, after: dict, cache: str | None) -> dict | None:
    if cache is None or cache not in after:
        return None
    prev = before.get(cache, {"hit": 0.0, "miss": 0.0, "errors": 0.0, "bypassed": 0.0})
    hits, misses, errors, bypassed = (int(after[cache][k] - prev[k]) for k in ("hit", "miss", "errors", "bypassed"))
    # Bypassed reads (Redis skipped after an error) are reported apart from the hit ratio.
    return {"cache": cache, "hits": hits, "misses": misses, "redis_errors": errors, "bypassed": bypassed,
            "hit_ratio": hits / (hits + misses) if hits + misses else None}


def redis_cli(*args: str) -> str:
    return compose("exec", "-T", "redis", "redis-cli", *args, capture=True).stdout.strip()


def flush_cache() -> None:
    redis_cli("FLUSHALL")


# --------------------------------------------------------------------------- Sprint 3: search

def backend_search_enabled() -> bool:
    out = compose("exec", "-T", "backend", "printenv", "SEARCH_ELASTICSEARCH_ENABLED",
                  capture=True, check=False).stdout.strip()
    return out == "true"


def backend_events_enabled() -> bool:
    out = compose("exec", "-T", "backend", "printenv", "EVENTS_ENABLED", capture=True, check=False).stdout.strip()
    return out == "true"


def es_get(path: str) -> dict:
    out = compose("exec", "-T", "elasticsearch", "curl", "--silent", "--fail", f"http://127.0.0.1:9200{path}",
                  capture=True).stdout
    return json.loads(out)


def admin_token() -> str:
    body = json.dumps({"email": ADMIN_EMAIL, "password": ADMIN_PASSWORD}).encode()
    request = urllib.request.Request(f"http://127.0.0.1:{BACKEND_PORT}/user/signIn", data=body,
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read())["token"]


def rebuild_search_index(expected: int, timeout_s: float = 600) -> None:
    """Recreates the index through the backend, waits for the backfill, and checks every product is indexed."""
    request = urllib.request.Request(f"http://127.0.0.1:{BACKEND_PORT}/product/search/rebuild", data=b"",
                                     method="POST", headers={"Authorization": f"Bearer {admin_token()}"})
    started = time.monotonic()
    with urllib.request.urlopen(request, timeout=60) as response:
        queued = json.loads(response.read())["queued"]
    log(f"search rebuild: {queued} index jobs queued")
    events = backend_events_enabled()
    while True:
        if events:
            # Sprint 5: outbox rows are kept after publishing, so wait for the documents themselves.
            es_get("/products/_refresh")
            pending = expected - es_get("/products/_count")["count"]
        else:
            pending = int(mysql("SELECT COUNT(*) FROM search_index_jobs", database=DATABASE)[0][0])
        if pending <= 0:
            break
        if time.monotonic() - started > timeout_s:
            raise SystemExit(f"search backfill still has {pending} jobs after {timeout_s:.0f}s")
        time.sleep(0.5)
    elapsed = time.monotonic() - started
    es_get("/products/_refresh")
    indexed = es_get("/products/_count")["count"]
    if indexed != expected:
        raise SystemExit(f"search index has {indexed} documents, expected {expected}")
    log(f"search backfill: {indexed} documents in {elapsed:.1f}s ({indexed / elapsed:.0f} docs/s)")


def http_json(method: str, path: str, body: dict | None = None, token: str | None = None) -> dict:
    headers = {"Content-Type": "application/json"} | ({"Authorization": f"Bearer {token}"} if token else {})
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(f"http://127.0.0.1:{BACKEND_PORT}{path}", data=data, method=method,
                                     headers=headers)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read())


def rename_product(product_id: int, name: str, token: str) -> None:
    current = http_json("GET", f"/product/{product_id}")
    http_json("POST", f"/product/update/{product_id}", {
        "name": name, "imageURL": current["imageURL"], "price": current["price"],
        "description": current["description"], "categoryId": current["categoryId"]}, token)


def visible(product_id: int, marker: str) -> bool:
    page = http_json("GET", f"/product/search?q={marker}&engine=elasticsearch&size=5")
    return any(item["id"] == product_id for item in page["items"])


def wait_visible(product_id: int, marker: str, interval_s: float, timeout_s: float = 60) -> float:
    started = time.monotonic()
    while not visible(product_id, marker):
        if time.monotonic() - started > timeout_s:
            raise SystemExit(f"product {product_id} not searchable as {marker!r} after {timeout_s:.0f}s")
        time.sleep(interval_s)
    return time.monotonic() - started


def percentile(values: list[float], p: float) -> float:
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, max(0, round(p * len(ordered)) - 1))]


def cmd_search_lag(args: argparse.Namespace) -> None:
    """Write-to-search visibility: admin rename, then poll search until the new name finds the product."""
    if not backend_search_enabled():
        raise SystemExit("Elasticsearch is off; run `bench.py up --search` first")
    token = admin_token()
    # The admin update requires a description, and some WANDS products have none.
    editable = [int(r[0]) for r in mysql("SELECT id FROM products WHERE description <> '' ORDER BY id",
                                         database=DATABASE)]
    total = len(editable)
    run_tag = stamp().lower().replace("t", "").replace("z", "")
    ids = [editable[(n * 7919) % total] for n in range(args.samples)]  # spread over the catalog, deterministic
    originals = {pid: http_json("GET", f"/product/{pid}")["name"] for pid in ids}
    result: dict = {"samples": args.samples, "probe_interval_ms": args.interval_ms, "started_at": utc_now(),
                    "products": total, "git": git_info()}
    try:
        lags = []
        for n, pid in enumerate(ids):
            marker = f"lagprobe{run_tag}n{n}"
            rename_product(pid, f"{originals[pid]} {marker}", token)  # returns after the commit
            lags.append(wait_visible(pid, marker, args.interval_ms / 1000))
            time.sleep(args.pause_ms / 1000)
        result["healthy_seconds"] = {"p50": percentile(lags, 0.5), "p95": percentile(lags, 0.95), "max": max(lags),
                                     "all": lags}
        log(f"healthy: {len(lags)} edits, visibility p50 {percentile(lags, 0.5):.2f}s, "
            f"p95 {percentile(lags, 0.95):.2f}s, max {max(lags):.2f}s (probe every {args.interval_ms} ms)")
        if args.outage:
            log(f"outage: pausing Elasticsearch, renaming {len(ids)} products, resuming after {args.outage}s")
            compose("pause", "elasticsearch", capture=True)
            try:
                for n, pid in enumerate(ids):
                    rename_product(pid, f"{originals[pid]} outage{run_tag}n{n}", token)
                time.sleep(args.outage)
                pending = int(mysql("SELECT COUNT(*) FROM search_index_jobs", database=DATABASE)[0][0])
            finally:
                compose("unpause", "elasticsearch", capture=True)
            resumed = time.monotonic()
            for n, pid in enumerate(ids):
                wait_visible(pid, f"outage{run_tag}n{n}", args.interval_ms / 1000, timeout_s=120)
            catch_up = time.monotonic() - resumed
            result["outage"] = {"seconds": args.outage, "edits": len(ids), "jobs_queued_at_resume": pending,
                                "catch_up_seconds": catch_up}
            log(f"outage: {pending} jobs queued during {args.outage}s; all {len(ids)} edits searchable "
                f"{catch_up:.2f}s after Elasticsearch resumed")
    finally:
        for pid, name in originals.items():
            rename_product(pid, name, token)
    out = RESULTS_DIR / "search-lag" / f"{stamp()}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, indent=2) + "\n")
    log(f"results: {out.relative_to(REPO_DIR)}")


# --------------------------------------------------------------------------- Sprint 5: catalog events

EVENT_GROUPS = ("search-indexer", "cache-invalidator")
EVENT_COUNTERS = {"events_effects_total": "effects", "events_duplicates_total": "duplicates",
                  "events_effect_failures_total": "failures"}
FAULTS = {
    # name: (service, action). kill-* restarts at once (a crash window); stop-* stays down for --fault-for.
    "kill-backend": ("backend", "kill"),
    "kill-worker": ("worker", "kill"),
    "stop-worker": ("worker", "stop"),
    "stop-es": ("elasticsearch", "stop"),
}


def outbox_max_id() -> int:
    return int(mysql("SELECT COALESCE(MAX(id), 0) FROM search_index_jobs", database=DATABASE)[0][0])


def pipeline_counts(first_id: int) -> dict:
    """Outbox rows from first_id, how many the broker has not accepted yet, and each group's committed effects."""
    groups = ", ".join(f"(SELECT COUNT(*) FROM consumed_events WHERE consumer_group = '{g}' AND event_id >= {first_id})"
                       for g in EVENT_GROUPS)
    row = mysql(f"SELECT (SELECT COUNT(*) FROM search_index_jobs WHERE id >= {first_id}), "
                f"(SELECT COUNT(*) FROM search_index_jobs WHERE id >= {first_id} AND published_at IS NULL), {groups}",
                database=DATABASE)[0]
    counts = {"outbox": int(row[0]), "unpublished": int(row[1])}
    for group, value in zip(EVENT_GROUPS, row[2:]):
        counts[group] = int(value)
    return counts


def worker_counters() -> dict[str, float] | None:
    """Per-group effect, duplicate-delivery and failure counters from the worker, or None while it is down."""
    out = compose("exec", "-T", "worker", "curl", "--silent", "--fail", "http://127.0.0.1:8081/actuator/prometheus",
                  capture=True, check=False).stdout
    if not out:
        return None
    totals: dict[str, float] = {}
    for line in out.splitlines():
        match = PROM_LINE_RE.match(line)
        if match and match.group(1) in EVENT_COUNTERS:
            group = dict(PROM_LABEL_RE.findall(match.group(2))).get("consumer", "?")
            key = f"{group}.{EVENT_COUNTERS[match.group(1)]}"
            totals[key] = totals.get(key, 0.0) + float(match.group(3))
    return totals


class CounterTotals:
    """Sums worker counters across worker restarts, which reset them to zero."""

    def __init__(self) -> None:
        self.last: dict[str, float] = {}
        self.offset: dict[str, float] = {}

    def add(self, sample: dict[str, float] | None) -> None:
        for key, value in (sample or {}).items():
            if value < self.last.get(key, 0.0):
                self.offset[key] = self.offset.get(key, 0.0) + self.last[key]
            self.last[key] = value

    def totals(self) -> dict[str, float]:
        return {key: self.offset.get(key, 0.0) + value for key, value in self.last.items()}


def dead_letters() -> dict[str, int]:
    """Messages in each group's dead-letter topic (0 when the broker never created it). One-off mqadmin container."""
    counts = {}
    for group in EVENT_GROUPS:
        # A missing topic (nothing dead-lettered yet) prints a stack trace on stderr; that means 0.
        out = subprocess.run(["docker", "run", "--rm", "--network", NETWORK, "-e", "JAVA_OPT_EXT=-Xmx256m",
                              ROCKETMQ_IMAGE, "sh", "mqadmin", "topicStatus", "-n", "rocketmq-namesrv:9876",
                              "-t", f"%DLQ%{group}"],
                             text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False).stdout
        total = 0
        for line in out.splitlines():
            parts = line.split()
            # Rows: broker name, queue id, min offset, max offset, last updated.
            if len(parts) >= 4 and parts[1].isdigit() and parts[3].isdigit():
                total += int(parts[3]) - int(parts[2])
        counts[group] = total
    return counts


def es_post(path: str, body: dict) -> dict:
    out = compose("exec", "-T", "elasticsearch", "curl", "--silent", "--fail", "-H", "Content-Type: application/json",
                  "-X", "POST", f"http://127.0.0.1:9200{path}", "--data-binary", "@-",
                  input_text=json.dumps(body), capture=True).stdout
    return json.loads(out)


def search_version_mismatches(first_id: int, last_id: int) -> dict:
    """Edited products whose search document version differs from products.search_version after the drain."""
    rows = mysql("SELECT p.id, p.search_version FROM products p JOIN (SELECT DISTINCT product_id FROM search_index_jobs"
                 f" WHERE id BETWEEN {first_id} AND {last_id} AND change_type = 'UPDATED') j ON j.product_id = p.id",
                 database=DATABASE)
    expected = {int(pid): int(version) for pid, version in rows}
    mismatched = []
    ids = sorted(expected)
    for start in range(0, len(ids), 1000):
        chunk = ids[start:start + 1000]
        docs = es_post("/products/_mget?_source=false", {"ids": [str(i) for i in chunk]})["docs"]
        for doc in docs:
            if not doc.get("found") or doc.get("_version") != expected[int(doc["_id"])]:
                mismatched.append(int(doc["_id"]))
    return {"edited_products": len(expected), "mismatched": len(mismatched), "examples": mismatched[:10]}


def reconcile(first_id: int, last_id: int) -> dict:
    """Matches every outbox ID in the range against each consumer group's ledger, with lag and throughput."""
    where = f"id BETWEEN {first_id} AND {last_id}"
    row = mysql(f"SELECT COUNT(*), SUM(published_at IS NULL), SUM(change_type = 'UPDATED'), SUM(change_type = 'STOCK'),"
                f" TIMESTAMPDIFF(MICROSECOND, MIN(created_at), MAX(created_at)) / 1e6 FROM search_index_jobs WHERE {where}",
                database=DATABASE)[0]
    total = int(row[0])
    span = float(row[4] or 0)
    result = {"first_id": first_id, "last_id": last_id, "expected_events": total,
              "unpublished": int(row[1] or 0), "updated": int(row[2] or 0), "stock": int(row[3] or 0),
              "input_span_s": span, "input_rate": total / span if span else None, "groups": {}}
    for group in EVENT_GROUPS:
        in_range = f"c.consumer_group = '{group}' AND c.event_id BETWEEN {first_id} AND {last_id}"
        g = mysql(f"SELECT COUNT(*), COUNT(DISTINCT c.event_id), SUM(j.id IS NULL),"
                  f" TIMESTAMPDIFF(MICROSECOND, MIN(c.consumed_at), MAX(c.consumed_at)) / 1e6"
                  f" FROM consumed_events c LEFT JOIN search_index_jobs j ON j.id = c.event_id WHERE {in_range}",
                  database=DATABASE)[0]
        lags = [int(r[0]) / 1000 for r in mysql(
            f"SELECT TIMESTAMPDIFF(MICROSECOND, c.event_created_at, c.consumed_at) FROM consumed_events c WHERE {in_range}",
            database=DATABASE)]
        peak = mysql(f"SELECT COALESCE(MAX(n), 0) FROM (SELECT COUNT(*) AS n FROM consumed_events c WHERE {in_range}"
                     " GROUP BY FLOOR(UNIX_TIMESTAMP(c.consumed_at))) t", database=DATABASE)[0][0]
        rows, distinct, orphans = int(g[0]), int(g[1]), int(g[2] or 0)
        effect_span = float(g[3] or 0)
        result["groups"][group] = {
            "ledger_rows": rows,
            "missing": total - (distinct - orphans),
            "duplicate_ledger_rows": rows - distinct,
            "orphan_ledger_rows": orphans,
            "completion_rate": rows / effect_span if effect_span else None,
            "peak_effects_per_s": int(peak),
            "lag_ms": ({"p50": percentile(lags, 0.50), "p95": percentile(lags, 0.95), "p99": percentile(lags, 0.99),
                        "max": max(lags)} if lags else None),
        }
    return result


def edit_fixture(path: Path) -> Path:
    """Every product's editable fields as JSON for k6, so an UPDATED edit re-saves the product unchanged."""
    rows = mysql("SELECT JSON_OBJECT('id', id, 'name', name, 'imageURL', image_url, 'price', ROUND(price_cents / 100, 2),"
                 " 'description', description, 'categoryId', category_id) FROM products"
                 " WHERE description <> '' AND name <> '' AND image_url <> '' ORDER BY id", database=DATABASE)
    unescape = {"n": "\n", "t": "\t", "0": "\0", "\\": "\\"}  # mysql -B escapes these in its output
    products = [json.loads(re.sub(r"\\(.)", lambda m: unescape.get(m.group(1), m.group(0)), "\t".join(r)))
                for r in rows]
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(products))
    return path


def wait_drained(timeout_s: float, sample) -> tuple[bool, float]:
    """Waits until sample() shows every outbox row published and applied by both groups."""
    started = time.monotonic()
    while True:
        counts = sample()
        if counts["unpublished"] == 0 and all(counts[g] >= counts["outbox"] for g in EVENT_GROUPS):
            return True, time.monotonic() - started
        if time.monotonic() - started > timeout_s:
            return False, time.monotonic() - started
        time.sleep(1)


def recovery(samples: list[dict], fault_at: float, restored_at: float) -> dict:
    """Peak backlog and the time after the restore until the backlog was back at its pre-fault level."""
    def backlog(s: dict) -> int:
        return max(s["outbox"] - s[g] for g in EVENT_GROUPS)
    before = [backlog(s) for s in samples if fault_at - 15 <= s["t"] < fault_at]
    baseline = max(before) if before else 0
    after = [s for s in samples if s["t"] >= fault_at]
    recovered = next((s["t"] for s in after if s["t"] >= restored_at and backlog(s) <= max(baseline, 1)), None)
    return {"pre_fault_backlog": baseline, "peak_backlog": max((backlog(s) for s in after), default=0),
            "peak_unpublished": max((s["unpublished"] for s in after), default=0),
            "recovered_s_after_restore": None if recovered is None else recovered - restored_at,
            "recovered_s_after_fault": None if recovered is None else recovered - fault_at}


def cmd_events_run(args: argparse.Namespace) -> None:
    """Admin edits at a fixed rate, then drain, reconcile every outbox ID, and optionally inject one fault."""
    if not backend_events_enabled():
        raise SystemExit("events are off; run `bench.py up --events` first")
    out_dir = RESULTS_DIR / "events" / (f"{stamp()}_{args.rate:g}eps" + (f"_{args.fault}" if args.fault else ""))
    data = edit_fixture(out_dir / "products.json")
    token = admin_token()
    env = {"BASE_URL": "http://backend:8080", "RATE": str(args.rate), "REQUEST_TIMEOUT": args.timeout,
           "GATE_P95_MS": str(args.p95_ms), "GATE_MAX_FAILURE_RATE": str(args.max_failure_rate),
           "TRACE_SEED": str(args.trace_seed), "ADMIN_TOKEN": token, "K6_NO_USAGE_REPORT": "true"}
    if duration_seconds(args.warmup) > 0:
        warm_first = outbox_max_id() + 1
        log(f"events @ {args.rate:g}/s: warm-up {args.warmup}")
        run_k6("catalog-edits.js", env | {"DURATION": args.warmup, "WARMUP": "1",
                                          "SUMMARY_FILE": "/results/warmup-summary.json"}, out_dir, data=data)
        drained, _ = wait_drained(args.drain_timeout, lambda: pipeline_counts(warm_first))
        if not drained:
            raise SystemExit("the pipeline did not drain after the warm-up; check the worker and broker")
    first_id = outbox_max_id() + 1
    counters = CounterTotals()
    counters.add(worker_counters())
    counters_before = counters.totals()
    samples: list[dict] = []
    t0 = time.monotonic()
    stop = threading.Event()

    def sample() -> dict:
        counts = pipeline_counts(first_id) | {"t": time.monotonic() - t0}
        samples.append(counts)
        return counts

    def sampler() -> None:
        while not stop.wait(1):
            try:
                sample()
                counters.add(worker_counters())
            except (subprocess.CalledProcessError, IndexError, ValueError):
                pass  # a container is restarting; the next sample catches up

    fault: dict = {}

    def inject() -> None:
        service, action = FAULTS[args.fault]
        time.sleep(args.fault_at)
        fault["at"] = time.monotonic() - t0
        if action == "kill":
            log(f"fault: SIGKILL {service} at {fault['at']:.1f}s, then start it again")
            compose("kill", "-s", "SIGKILL", service, capture=True)
        else:
            log(f"fault: stopping {service} at {fault['at']:.1f}s for {args.fault_for:g}s")
            compose("stop", service, capture=True)
            time.sleep(args.fault_for)
        compose("start", service, capture=True)
        fault["restored_at"] = time.monotonic() - t0
        log(f"fault: {service} started again at {fault['restored_at']:.1f}s")

    threads = [threading.Thread(target=sampler, daemon=True)]
    if args.fault:
        threads.append(threading.Thread(target=inject, daemon=True))
    for thread in threads:
        thread.start()
    log(f"events @ {args.rate:g}/s: measuring {args.duration} from outbox id {first_id}"
        + (f", fault {args.fault} at {args.fault_at:g}s" if args.fault else ""))
    started = utc_now()
    code = run_k6("catalog-edits.js", env | {"DURATION": args.duration, "SUMMARY_FILE": "/results/summary.json"},
                  out_dir, data=data)
    load_done = time.monotonic() - t0
    if args.fault:
        threads[1].join()
    last_id = outbox_max_id()
    drained, drain_s = wait_drained(args.drain_timeout, sample)
    stop.set()
    threads[0].join()
    counters.add(worker_counters())
    summary_path = out_dir / "summary.json"
    summary = json.loads(summary_path.read_text()) if summary_path.exists() else {}
    latency = metric(summary, f"http_req_duration{{route:catalog_edit}}")
    after = counters.totals()
    result = {
        "rate": args.rate, "duration": args.duration, "warmup": args.warmup, "started_at": started,
        "k6": {"exit_code": code, "requests": metric(summary, "http_reqs{route:catalog_edit}").get("count", 0),
               "successful": metric(summary, "successful_requests").get("count", 0),
               "unexpected_failure_rate": metric(summary, "unexpected_failures").get("rate", 0.0),
               "dropped_iterations": metric(summary, "dropped_iterations").get("count", 0),
               "latency_ms": {k: latency.get(k) for k in ("med", "p(95)", "p(99)", "max")}},
        "load_seconds": load_done, "drained": drained, "drain_seconds_after_load": drain_s,
        "reconcile": reconcile(first_id, last_id),
        "worker_counters": {k: after.get(k, 0.0) - counters_before.get(k, 0.0) for k in sorted(after)},
        "search_versions": search_version_mismatches(first_id, last_id),
        "dead_letters": dead_letters(),
        "fault": ({"name": args.fault, **fault, **recovery(samples, fault["at"], fault["restored_at"])}
                  if args.fault else None),
        "settings": {"max_reconsume": compose("exec", "-T", "worker", "printenv", "EVENTS_CONSUMER_MAX_RECONSUME_TIMES",
                                              capture=True, check=False).stdout.strip(),
                     "limits": limits(), "trace_seed": args.trace_seed},
        "git": git_info(), "host": host_info(),
    }
    (out_dir / "samples.json").write_text(json.dumps(samples) + "\n")
    (out_dir / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print_events_result(result)
    log(f"results: {out_dir.relative_to(REPO_DIR)}")


def print_events_result(result: dict) -> None:
    rec = result["reconcile"]
    k6 = result["k6"]
    log(f"input: {rec['expected_events']} events ({rec['updated']} UPDATED, {rec['stock']} STOCK) at "
        f"{fmt(rec['input_rate'])}/s; k6 p95 {fmt(k6['latency_ms']['p(95)'])} ms, "
        f"{k6['unexpected_failure_rate'] * 100:.2f}% failed, {k6['dropped_iterations']} dropped")
    for group, g in rec["groups"].items():
        lag = g["lag_ms"] or {}
        log(f"{group}: missing {g['missing']}, duplicate ledger rows {g['duplicate_ledger_rows']}, "
            f"{fmt(g['completion_rate'])}/s (peak {g['peak_effects_per_s']}/s), lag ms p50 {fmt(lag.get('p50'))} "
            f"p95 {fmt(lag.get('p95'))} p99 {fmt(lag.get('p99'))} max {fmt(lag.get('max'))}")
    log(f"drained: {result['drained']} ({result['drain_seconds_after_load']:.1f}s after load); "
        f"worker counters {result['worker_counters']}; search versions {result['search_versions']}; "
        f"dead letters {result['dead_letters']}")
    if result.get("fault"):
        log(f"fault: {result['fault']}")


def cmd_events_replay(args: argparse.Namespace) -> None:
    """Operator replay: re-publishes, under their original IDs, the run's events a group never applied."""
    run_dir = Path(args.run) if Path(args.run).exists() else RESULTS_DIR / "events" / args.run
    result = json.loads((run_dir / "result.json").read_text())
    first_id, last_id = result["reconcile"]["first_id"], result["reconcile"]["last_id"]
    groups = [args.group] if args.group else list(EVENT_GROUPS)
    ids: set[int] = set()
    if args.all:
        # Every event again, as if the publisher crashed after each send and before marking it published.
        ids = {int(r[0]) for r in mysql(f"SELECT id FROM search_index_jobs WHERE id BETWEEN {first_id} AND {last_id}",
                                        database=DATABASE)}
    for group in ([] if args.all else groups):
        ids |= {int(r[0]) for r in mysql(
            f"SELECT j.id FROM search_index_jobs j LEFT JOIN consumed_events c ON c.event_id = j.id"
            f" AND c.consumer_group = '{group}' WHERE j.id BETWEEN {first_id} AND {last_id} AND c.event_id IS NULL",
            database=DATABASE)}
    log(f"replay: {len(ids)} events in {first_id}..{last_id} "
        + ("(all of them)" if args.all else f"lack an effect in {', '.join(groups)}"))
    counters = CounterTotals()
    counters.add(worker_counters())
    before = counters.totals()
    started = time.monotonic()
    ordered = sorted(ids)
    for start in range(0, len(ordered), 1000):
        chunk = ",".join(map(str, ordered[start:start + 1000]))
        # Clearing published_at hands the rows back to the publisher, which sends them with the same event IDs.
        mysql(f"UPDATE search_index_jobs SET published_at = NULL WHERE id IN ({chunk})", database=DATABASE)
    drained, seconds = wait_drained(args.drain_timeout, lambda: pipeline_counts(first_id))
    counters.add(worker_counters())
    after = counters.totals()
    replay = {"replayed": len(ids), "all": args.all, "groups": groups, "drained": drained,
              "seconds": time.monotonic() - started, "reconcile": reconcile(first_id, last_id),
              "worker_counters": {k: after.get(k, 0.0) - before.get(k, 0.0) for k in sorted(after)},
              "search_versions": search_version_mismatches(first_id, last_id), "dead_letters": dead_letters()}
    name = "resend.json" if args.all else "replay.json"
    (run_dir / name).write_text(json.dumps(replay, indent=2) + "\n")
    log(f"replay: drained {drained} in {replay['seconds']:.1f}s; worker counters {replay['worker_counters']}")
    for group, g in replay["reconcile"]["groups"].items():
        log(f"{group}: missing {g['missing']}, duplicate ledger rows {g['duplicate_ledger_rows']}")
    log(f"search versions {replay['search_versions']}; results: {(run_dir / name).relative_to(REPO_DIR)}")


# --------------------------------------------------------------------------- k6

def k6_env(scenario: str, args: argparse.Namespace, fixture: dict, rate: float, duration: str) -> dict[str, str]:
    env = {
        "BASE_URL": "http://backend:8080",
        "RATE": str(rate),
        "DURATION": duration,
        "REQUEST_TIMEOUT": args.timeout,
        "GATE_P95_MS": str(args.p95_ms),
        "GATE_MAX_FAILURE_RATE": str(args.max_failure_rate),
        "TRACE_SEED": str(args.trace_seed),
        "TOTAL_PRODUCTS": str(fixture["total_products"]),
        "K6_NO_USAGE_REPORT": "true",
    }
    if fixture.get("dataset", "synthetic") == "synthetic":
        env |= {
            "PRODUCT_COUNT": str(fixture["catalog_products"]),
            "HOT_SKU_ID": str(fixture["hot_sku"]["id"]),
            "CART_USERS": str(fixture["carts_with_hot_sku"]),
            "FIRST_USER_ID": str(fixture["users"]["first_id"]),
            "BENCH_PASSWORD": fixture["users"]["password"],
        }
    if scenario == "detail":
        env["PATTERN"] = args.pattern
    if scenario == "page":
        env["SORT"] = args.sort
        env["SIZE"] = str(args.size)
        env["CATEGORIES"] = str(fixture["categories"])
    if scenario == "search":
        env["ENGINE"] = args.engine
        env["SIZE"] = str(args.size)
    for item in getattr(args, "env", None) or []:
        key, _, value = item.partition("=")
        env[key] = value
    return env


def search_query_trace() -> Path:
    """The WANDS query strings (both splits; this is a latency trace, not a relevance score) for k6."""
    path = WANDS_DIR / "k6-queries.json"
    queries = json.loads((WANDS_DIR / "queries.json").read_text())
    path.write_text(json.dumps([q["query"] for q in sorted(queries, key=lambda q: q["query_id"])]))
    return path


def run_k6(script: str, env: dict[str, str], results_dir: Path, tokens: Path | None = None,
           queries: Path | None = None, data: Path | None = None) -> int:
    results_dir.mkdir(parents=True, exist_ok=True)
    cmd = [
        "docker", "run", "--rm", "--network", NETWORK,
        "--cpus", limits()["BENCH_K6_CPUS"],
        "--user", f"{os.getuid()}:{os.getgid()}",
        "-v", f"{BENCH_DIR / 'k6'}:/scripts:ro",
        "-v", f"{results_dir}:/results",
    ]
    if tokens is not None:
        cmd += ["-v", f"{tokens}:/tokens.json:ro"]
    if queries is not None:
        cmd += ["-v", f"{queries}:/queries.json:ro"]
    if data is not None:
        cmd += ["-v", f"{data}:/data.json:ro"]
    for key, value in env.items():
        cmd += ["-e", f"{key}={value}"]
    cmd += [K6_IMAGE, "run", "--quiet", f"/scripts/{script}"]
    return sh(cmd, check=False).returncode


def metric(summary: dict, name: str) -> dict:
    return summary.get("metrics", {}).get(name, {}).get("values", {})


def summarize_trial(trial: int, code: int, summary: dict, route: str, rate: float,
                    duration: str, sql: dict) -> dict:
    latency = metric(summary, f"http_req_duration{{route:{route}}}") or metric(summary, "http_req_duration")
    # Per-route request count keeps setup() traffic (sign-ins) out of delivered load.
    requests = metric(summary, f"http_reqs{{route:{route}}}") or metric(summary, "http_reqs")
    successes = metric(summary, "successful_requests").get("count", 0)
    seconds = duration_seconds(duration)
    return {
        "trial": trial,
        "k6_exit_code": code,
        "gate_passed": code == 0,
        "scheduled_iterations": round(rate * seconds),
        "iterations": metric(summary, "iterations").get("count", 0),
        "dropped_iterations": metric(summary, "dropped_iterations").get("count", 0),
        "requests": requests.get("count", 0),
        # k6's own rate divides by the whole test time, including setup(); use the scenario window.
        "delivered_rps": requests.get("count", 0) / seconds if seconds else 0.0,
        "successful_requests": successes,
        "successful_rps": successes / seconds if seconds else 0.0,
        "unexpected_failure_rate": metric(summary, "unexpected_failures").get("rate", 0.0),
        "latency_ms": {k: latency.get(k) for k in ("med", "p(90)", "p(95)", "p(99)", "max")},
        "response_bytes_avg": metric(summary, "response_bytes").get("avg"),
        "sql": {k: sql[k] for k in ("all_statements", "all_selects", "app_table_reads", "app_rows_examined")},
        "app_sql_reads_per_success": (sql["app_table_reads"] / successes) if successes else None,
    }


def sign_in(fixture: dict, count: int, out_path: Path) -> Path:
    """Signs the first `count` seeded shoppers in over HTTP and writes their tokens for k6.

    Done before any measured window, so bcrypt sign-ins and their user lookups never land in the SQL
    digests or the latency numbers of the run.
    """
    users = fixture["users"]
    if count > users["count"]:
        raise SystemExit(f"fixture has {users['count']} shoppers, {count} requested")
    tokens = []
    for n in range(1, count + 1):
        email = users["email_pattern"].replace("{n:04d}", f"{n:04d}")
        body = json.dumps({"email": email, "password": users["password"]}).encode()
        request = urllib.request.Request(f"http://127.0.0.1:{BACKEND_PORT}/user/signIn", data=body,
                                         headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                tokens.append(json.loads(response.read())["token"])
        except (urllib.error.URLError, KeyError) as exc:
            raise SystemExit(f"sign-in failed for {email}: {exc}")
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(tokens))
    log(f"signed in {count} shoppers before measuring")
    return out_path


def run_trials(scenario: str, args: argparse.Namespace, fixture: dict, rate: float, out_dir: Path,
               trials: int, warmup: str, duration: str) -> list[dict]:
    spec = SCENARIOS[scenario]
    results = []
    tokens = sign_in(fixture, fixture["carts_with_hot_sku"], out_dir / "tokens.json") if spec.get("tokens") else None
    queries = search_query_trace() if spec.get("queries") else None
    for trial in range(1, trials + 1):
        trial_dir = out_dir / f"trial-{trial}"
        if getattr(args, "flush_cache", False):
            log(f"trial {trial}: flushing Redis, so the trial starts from an empty cache")
            flush_cache()
        if duration_seconds(warmup) > 0:
            log(f"{scenario} @ {rate}/s trial {trial}/{trials}: warm-up {warmup}")
            env = k6_env(scenario, args, fixture, rate, warmup) | {
                "WARMUP": "1", "SUMMARY_FILE": "/results/warmup-summary.json"}
            run_k6(spec["script"], env, trial_dir, tokens, queries)
        log(f"{scenario} @ {rate}/s trial {trial}/{trials}: measuring {duration}")
        before = digest_snapshot()
        cache_before = cache_snapshot()
        started = utc_now()
        env = k6_env(scenario, args, fixture, rate, duration) | {"SUMMARY_FILE": "/results/summary.json"}
        code = run_k6(spec["script"], env, trial_dir, tokens, queries)
        finished = utc_now()
        sql = digest_delta(before, digest_snapshot())
        cache = cache_delta(cache_before, cache_snapshot(), spec.get("cache"))
        summary_path = trial_dir / "summary.json"
        summary = json.loads(summary_path.read_text()) if summary_path.exists() else {}
        result = summarize_trial(trial, code, summary, spec["route"], rate, duration, sql)
        result["measured_window"] = {"started_at": started, "finished_at": finished}
        result["cache"] = cache
        # A sleeping laptop or paused Docker VM stretches the window and corrupts latency.
        elapsed = (dt.datetime.fromisoformat(finished.replace("Z", "+00:00"))
                   - dt.datetime.fromisoformat(started.replace("Z", "+00:00"))).total_seconds()
        allowed = duration_seconds(duration) * 1.2 + 60
        result["wall_clock_seconds"] = elapsed
        if elapsed > allowed:
            result["gate_passed"] = False
            result["invalid"] = f"measured window took {elapsed:.0f}s for a {duration} run; host paused or slept"
            log(f"INVALID trial {trial}: {result['invalid']}")
        (trial_dir / "sql-digests.json").write_text(json.dumps(sql, indent=2) + "\n")
        (trial_dir / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        results.append(result)
        if code not in (0, 99):
            log(f"k6 exited with {code} (not a threshold failure); see output above")
    return results


# --------------------------------------------------------------------------- reports

def fmt(value, digits: int = 1) -> str:
    return "n/a" if value is None else f"{value:.{digits}f}"


def trial_table(results: list[dict]) -> list[str]:
    lines = [
        "| Trial | Gate | Scheduled | Delivered req/s | Successful req/s | p50 ms | p95 ms | p99 ms "
        "| Unexpected failures | Dropped | App SQL reads / success | Cache hit % | Redis errors | Bypassed "
        "| Avg response bytes |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for r in results:
        lat = r["latency_ms"]
        gate = "INVALID" if r.get("invalid") else ("pass" if r["gate_passed"] else "FAIL")
        lines.append(
            f"| {r['trial']} | {gate} | {r['scheduled_iterations']} "
            f"| {fmt(r['delivered_rps'])} | {fmt(r['successful_rps'])} | {fmt(lat['med'])} "
            f"| {fmt(lat['p(95)'])} | {fmt(lat['p(99)'])} | {fmt(r['unexpected_failure_rate'] * 100, 2)}% "
            f"| {r['dropped_iterations']} | {fmt(r['app_sql_reads_per_success'], 2)} "
            f"| {cache_hit_pct(r)} | {(r.get('cache') or {}).get('redis_errors', 'n/a')} "
            f"| {(r.get('cache') or {}).get('bypassed', 'n/a')} "
            f"| {fmt(r['response_bytes_avg'], 0)} |")
    return lines


def cache_hit_pct(result: dict) -> str:
    ratio = (result.get("cache") or {}).get("hit_ratio")
    return "n/a" if ratio is None else f"{ratio * 100:.1f}%"


def write_run_report(out_dir: Path, manifest: dict, results: list[dict]) -> None:
    p95s = [r["latency_ms"]["p(95)"] for r in results if r["latency_ms"]["p(95)"] is not None]
    fixture = manifest["fixture"]
    lim = manifest["limits"]
    lines = [
        f"# {manifest['scenario']} @ {manifest['rate']}/s"
        + (f" ({manifest['pattern']})" if manifest.get("pattern") else "")
        + (f" (sort={manifest['sort']}, size={manifest['size']})" if manifest.get("sort") else ""),
        "",
        f"Reproduce: `{manifest['command']}`",
        "",
        f"- Commit `{manifest['git']['commit'][:12]}` on `{manifest['git']['branch']}`"
        + (" with uncommitted changes" if manifest["git"]["dirty"] else ""),
        (f"- WANDS fixture v{fixture['fixture_version']}, {fixture['total_products']} products, "
         f"sha256 `{fixture['sql_sha256'][:12]}`; engine {manifest.get('engine')}"
         if fixture.get("dataset") == "wands" else
         f"- Fixture v{fixture['fixture_version']}, seed {fixture['seed']}, {fixture['catalog_products']} catalog "
         f"products, sha256 `{fixture['sql_sha256'][:12]}`"),
        f"- Warm-up {manifest['warmup']}, measured {manifest['duration']}, trace seed {manifest['trace_seed']}, "
        f"request timeout {manifest['request_timeout']}",
        f"- Catalog cache: {manifest['cache']['type']}"
        + (f" (TTL {manifest['cache']['ttl']})" if manifest["cache"]["type"] == "redis" else "")
        + ("; Redis flushed before each trial" if manifest.get("flush_cache") else ""),
        f"- Gate: p95 < {manifest['gate']['p95_ms']} ms, unexpected failures < "
        f"{manifest['gate']['max_failure_rate'] * 100:g}%, zero dropped iterations",
        f"- Limits: backend {lim['BENCH_BACKEND_CPUS']} CPU / {lim['BENCH_BACKEND_MEM']}, MySQL "
        f"{lim['BENCH_MYSQL_CPUS']} CPU / {lim['BENCH_MYSQL_MEM']}, k6 {lim['BENCH_K6_CPUS']} CPU "
        "(k6 runs in Docker on the same host)",
        "",
        *trial_table(results),
        "",
    ]
    if p95s:
        lines.append(f"Median p95 across trials: **{statistics.median(p95s):.1f} ms** "
                     f"(range {min(p95s):.1f}–{max(p95s):.1f} ms).")
        lines.append("")
    first = json.loads((out_dir / "trial-1" / "sql-digests.json").read_text())
    lines += ["Top SQL statements in trial 1 (measured window):", "",
              "| Count | Rows examined | App read | Statement |", "| --- | --- | --- | --- |"]
    for s in first["statements"][:8]:
        text = s["text"][:140].replace("|", "\\|") + ("…" if len(s["text"]) > 140 else "")
        lines.append(f"| {s['count']} | {s['rows_examined']} | {'yes' if s['app_table_read'] else 'no'} | `{text}` |")
    lines.append("")
    (out_dir / "report.md").write_text("\n".join(lines))


def run_manifest(scenario: str, args: argparse.Namespace, fixture: dict, rate: float,
                 trials: int, warmup: str, duration: str) -> dict:
    return {
        "command": "python3 benchmarks/bench.py " + shlex.join(sys.argv[1:]),
        "scenario": scenario,
        "script": SCENARIOS[scenario]["script"],
        "rate": rate,
        "pattern": args.pattern if scenario == "detail" else None,
        "sort": args.sort if scenario == "page" else None,
        "size": args.size if scenario in ("page", "search") else None,
        "engine": args.engine if scenario == "search" else None,
        "trials": trials,
        "warmup": warmup,
        "duration": duration,
        "trace_seed": args.trace_seed,
        "request_timeout": args.timeout,
        "gate": {"p95_ms": args.p95_ms, "max_failure_rate": args.max_failure_rate, "dropped_iterations": 0},
        "extra_env": getattr(args, "env", None) or [],
        "cache": backend_cache_config(),
        "flush_cache": getattr(args, "flush_cache", False),
        "fixture": fixture,
        "limits": limits(),
        "images": image_ids(),
        "git": git_info(),
        "host": host_info(),
        "k6_network": NETWORK,
        "started_at": utc_now(),
    }


# --------------------------------------------------------------------------- commands

def create_event_topic() -> None:
    """Creates the ProductChanged topic (the broker never auto-creates topics). Idempotent.

    mqadmin runs in a one-off container on the bench network: inside the broker container its JVM would share the
    broker's memory limit."""
    network = f"{PROJECT}_default"
    deadline = time.monotonic() + 120
    while True:
        out = sh(["docker", "run", "--rm", "--network", network, "-e", "JAVA_OPT_EXT=-Xmx256m", ROCKETMQ_IMAGE,
                  "sh", "mqadmin", "updateTopic", "-n", "rocketmq-namesrv:9876", "-c", "bench", "-t", EVENT_TOPIC],
                 capture=True, check=False).stdout
        if "success" in out:
            log(f"RocketMQ topic {EVENT_TOPIC} ready")
            return
        # The broker reports healthy before its first registration with the name server.
        if time.monotonic() > deadline:
            raise SystemExit(f"creating RocketMQ topic {EVENT_TOPIC} failed:\n{out}")
        time.sleep(2)


def cmd_up(args: argparse.Namespace) -> None:
    events = bool(getattr(args, "events", False)) or os.environ.get("BENCH_EVENTS", "false") == "true"
    os.environ["BENCH_EVENTS"] = "true" if events else "false"
    # Both consumer groups need their stores: search-indexer (Elasticsearch) and cache-invalidator (Redis cache).
    cache = "redis" if events else (getattr(args, "cache", None) or os.environ.get("BENCH_CACHE", "none"))
    os.environ["BENCH_CACHE"] = cache
    search = events or bool(getattr(args, "search", False)) or os.environ.get("BENCH_SEARCH", "false") == "true"
    os.environ["BENCH_SEARCH"] = "true" if search else "false"
    os.environ["BENCH_SEARCH_ENGINE"] = "elasticsearch" if search else "mysql"
    # With events the consumer groups run in their own worker container, so the backend only publishes.
    os.environ["BENCH_BACKEND_CONSUMERS"] = "false" if events else "true"
    if events:
        compose("up", "-d", "--wait", "rocketmq-namesrv", "rocketmq-broker")
        create_event_topic()
    else:
        compose("rm", "--stop", "--force", "worker", capture=True, check=False)
    services = (["mysql", "redis"] + (["elasticsearch"] if search else []) + ["backend"] + (["worker"] if events else [])
                + (["prometheus", "grafana"] if args.observability else []))
    compose("up", "-d", "--build", "--wait", *services, observability=args.observability)
    log(f"stack {PROJECT}: backend on http://127.0.0.1:{BACKEND_PORT} (actuator stays on the internal port 8081), "
        f"catalog cache {backend_cache_config()['type']}, Elasticsearch {'on' if search else 'off'}, "
        f"RocketMQ events {'on' if events else 'off'}")
    if cache == "redis":
        flush_cache()
    if args.observability:
        log(f"Grafana on http://127.0.0.1:{os.environ.get('BENCH_GRAFANA_PORT', '3001')}, "
            f"Prometheus on http://127.0.0.1:{os.environ.get('BENCH_PROMETHEUS_PORT', '9090')}")


def cmd_down(args: argparse.Namespace) -> None:
    compose("down", *(["--volumes"] if args.volumes else []), observability=True)


def cmd_seed(args: argparse.Namespace) -> None:
    sh([sys.executable, "-I", str(GENERATOR), "--out-dir", str(FIXTURE_DIR), "--seed", str(args.seed),
        "--products", str(args.products), "--users", str(args.users), "--cart-users", str(args.cart_users),
        "--hot-sku-stock", str(args.hot_sku_stock)])
    manifest = json.loads((FIXTURE_DIR / "manifest.json").read_text())
    log(f"loading fixture into {DATABASE}")
    with open(FIXTURE_DIR / "fixture.sql") as sql:
        compose("exec", "-T", "-e", f"MYSQL_PWD={MYSQL_ROOT_PASSWORD}", "mysql",
                "mysql", "-uroot", DATABASE, stdin=sql, capture=True)
    counts = mysql(
        "SELECT (SELECT COUNT(*) FROM products), (SELECT COUNT(*) FROM users WHERE id >= 1001), "
        "(SELECT COUNT(*) FROM cart), (SELECT stock FROM products WHERE id = "
        f"{manifest['hot_sku']['id']})", database=DATABASE)[0]
    expected = [manifest["total_products"], manifest["users"]["count"],
                manifest["carts_with_hot_sku"], manifest["hot_sku"]["stock"]]
    if [int(c) for c in counts] != expected:
        raise SystemExit(f"fixture verification failed: got {counts}, expected {expected}")
    # Rows changed under the cache, so drop anything cached from the previous fixture.
    flush_cache()
    log(f"verified {counts[0]} products, {counts[1]} users, {counts[2]} carts, hot SKU stock {counts[3]}")
    if backend_search_enabled():
        rebuild_search_index(manifest["total_products"])


def cmd_seed_wands(args: argparse.Namespace) -> None:
    """Loads the WANDS catalog (Sprint 3 relevance and search load runs) instead of the synthetic fixture."""
    raw = WANDS_DIR / "raw"
    missing = [f for f in ("product.csv", "query.csv", "label.csv") if not (raw / f).exists()]
    if missing:
        raise SystemExit(f"missing {', '.join(missing)} in {raw.relative_to(REPO_DIR)}; download them with\n  "
                         + "\n  ".join(f"curl -fL -o {raw.relative_to(REPO_DIR)}/{f} {WANDS_URL}/{f}" for f in missing))
    sh([sys.executable, "-I", str(WANDS_LOADER), "--raw-dir", str(raw), "--out-dir", str(WANDS_DIR),
        "--split-seed", str(args.split_seed)])
    manifest = json.loads((WANDS_DIR / "manifest.json").read_text())
    log(f"loading WANDS into {DATABASE}")
    with open(WANDS_DIR / "wands.sql") as sql:
        compose("exec", "-T", "-e", f"MYSQL_PWD={MYSQL_ROOT_PASSWORD}", "mysql",
                "mysql", "-uroot", "--default-character-set=utf8mb4", DATABASE, stdin=sql, capture=True)
    count = int(mysql("SELECT COUNT(*) FROM products", database=DATABASE)[0][0])
    if count != manifest["total_products"]:
        raise SystemExit(f"WANDS verification failed: {count} products, expected {manifest['total_products']}")
    FIXTURE_DIR.mkdir(parents=True, exist_ok=True)
    (FIXTURE_DIR / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    flush_cache()
    log(f"verified {count} WANDS products in {manifest['categories']} categories")
    if backend_search_enabled():
        rebuild_search_index(count)
    else:
        log("Elasticsearch is off; run `up --search` and `seed-wands` again to index the catalog")


def check_dataset(scenario: str, fixture: dict) -> None:
    """The search scenario needs the WANDS catalog (real queries); the others need the synthetic fixture."""
    dataset = fixture.get("dataset", "synthetic")
    if (scenario == "search") != (dataset == "wands"):
        raise SystemExit(f"scenario {scenario} does not run on the {dataset} fixture; "
                         + ("run `bench.py seed-wands`" if scenario == "search" else "run `bench.py seed`"))


def cmd_run(args: argparse.Namespace) -> None:
    fixture = load_fixture_manifest()
    check_dataset(args.scenario, fixture)
    rate = args.rate if args.rate is not None else SCENARIOS[args.scenario]["rate"]
    label = (f"{args.scenario}_{rate:g}rps" + (f"_{args.pattern}" if args.scenario == "detail" else "")
             + (f"_{args.sort}" if args.scenario == "page" else "")
             + (f"_{args.engine}" if args.scenario == "search" else ""))
    out_dir = RESULTS_DIR / "runs" / f"{stamp()}_{label}"
    manifest = run_manifest(args.scenario, args, fixture, rate, args.trials, args.warmup, args.duration)
    results = run_trials(args.scenario, args, fixture, rate, out_dir, args.trials, args.warmup, args.duration)
    manifest["finished_at"] = utc_now()
    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (out_dir / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    write_run_report(out_dir, manifest, results)
    print("\n" + "\n".join(trial_table(results)) + "\n")
    log(f"report: {(out_dir / 'report.md').relative_to(REPO_DIR)}")
    if not all(r["gate_passed"] for r in results):
        sys.exit(1)


def cmd_ladder(args: argparse.Namespace) -> None:
    fixture = load_fixture_manifest()
    check_dataset(args.scenario, fixture)
    rates = [float(r) for r in args.rates.split(",")]
    label = (f"ladder_{args.scenario}" + (f"_{args.pattern}" if args.scenario == "detail" else "")
             + (f"_{args.engine}" if args.scenario == "search" else ""))
    out_dir = RESULTS_DIR / "runs" / f"{stamp()}_{label}"
    manifest = run_manifest(args.scenario, args, fixture, rates[0], 1, args.warmup, args.duration)
    manifest["rates"] = rates
    steps = []
    for rate in rates:
        result = run_trials(args.scenario, args, fixture, rate, out_dir / f"rate-{rate:g}", 1,
                            args.warmup, args.duration)[0]
        result["rate"] = rate
        steps.append(result)
        if not result["gate_passed"] and not args.keep_going:
            break
    manifest["finished_at"] = utc_now()
    passing = [s["rate"] for s in steps if s["gate_passed"]]
    failing = [s["rate"] for s in steps if not s["gate_passed"]]
    lines = [f"# Load ladder: {args.scenario}", "", f"Reproduce: `{manifest['command']}`", "",
             "| Offered req/s | Gate | Delivered req/s | Successful req/s | p95 ms | p99 ms "
             "| Unexpected failures | Dropped | App SQL reads / success |",
             "| --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
    for s in steps:
        lines.append(
            f"| {s['rate']:g} | {'pass' if s['gate_passed'] else 'FAIL'} | {fmt(s['delivered_rps'])} "
            f"| {fmt(s['successful_rps'])} | {fmt(s['latency_ms']['p(95)'])} | {fmt(s['latency_ms']['p(99)'])} "
            f"| {fmt(s['unexpected_failure_rate'] * 100, 2)}% | {s['dropped_iterations']} "
            f"| {fmt(s['app_sql_reads_per_success'], 2)} |")
    lines.append("")
    if passing:
        best = max(passing)
        confirm = (f"python3 benchmarks/bench.py run {args.scenario} --rate {best:g}"
                   + (f" --pattern {args.pattern}" if args.scenario == "detail" else "") + " --trials 3")
        lines.append(f"Highest passing step: **{best:g} req/s**"
                     + (f"; first failing step: {min(failing):g} req/s." if failing else "; no step failed."))
        lines.append(f"Confirm it with three full trials: `{confirm}`")
    else:
        lines.append("No step passed. Lower the first rate or check the stack.")
    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (out_dir / "ladder.json").write_text(json.dumps(steps, indent=2) + "\n")
    (out_dir / "report.md").write_text("\n".join(lines) + "\n")
    print("\n" + "\n".join(lines[4:]) + "\n")
    log(f"report: {(out_dir / 'report.md').relative_to(REPO_DIR)}")


def cmd_smoke(args: argparse.Namespace) -> None:
    """Small end-to-end run: correct responses pass the gate, wrong ones fail it."""
    cmd_up(argparse.Namespace(observability=False, cache=None))
    cmd_seed(argparse.Namespace(seed=20261109, products=500, users=20, cart_users=20, hot_sku_stock=20))
    fixture = load_fixture_manifest()
    out_dir = RESULTS_DIR / "runs" / f"{stamp()}_smoke"
    checks = [
        ("detail returns the requested product", "detail", 10, [], True),
        ("a wrong expected id fails the checks", "detail", 10, ["EXPECT_WRONG_ID=1"], False),
        ("list returns the whole catalog", "list", 2, [], True),
        ("seeded shoppers can sign in and read their cart", "cart", 5, [], True),
        ("category page is full and sorted by price", "page", 10, [], True),
    ]
    failures = []
    for title, scenario, rate, env, should_pass in checks:
        run_args = argparse.Namespace(**vars(args), pattern="uniform", env=env, sort="price_asc", size=20)
        result = run_trials(scenario, run_args, fixture, rate, out_dir / re.sub(r"\W+", "-", title),
                            1, "0s", "15s")[0]
        ok = result["gate_passed"] == should_pass and result["k6_exit_code"] in (0, 99)
        log(f"{'ok  ' if ok else 'FAIL'} {title} (k6 exit {result['k6_exit_code']})")
        if not ok:
            failures.append(title)
    burst = burst_trials(fixture, 20, 5, 1, out_dir / "checkout-burst", args.timeout)[0]
    log(f"{'ok  ' if burst['passed'] else 'FAIL'} checkout burst keeps inventory invariants (20 shoppers, 5 units)")
    if not burst["passed"]:
        failures.append("checkout burst")
    if failures:
        raise SystemExit(f"smoke failed: {', '.join(failures)}")
    log("smoke passed. Re-seed before real runs: python3 benchmarks/bench.py seed")


def cmd_cache(args: argparse.Namespace) -> None:
    """Inspect the Redis cache, or inject an outage by stopping it (the backend keeps running)."""
    if args.action == "flush":
        flush_cache()
        log("Redis flushed")
    elif args.action == "stop":
        compose("stop", "redis")
        log("Redis stopped; catalog reads now fall back to MySQL. Restore with: bench.py cache start")
    elif args.action == "start":
        compose("start", "redis")
        compose("up", "-d", "--wait", "--no-recreate", "redis")
        log("Redis started (empty: it keeps nothing across restarts)")
    elif args.action == "pause":
        compose("pause", "redis")
        log("Redis paused: connections stay open but nothing answers (a hung Redis). Undo with: bench.py cache unpause")
    elif args.action == "unpause":
        compose("unpause", "redis")
        log("Redis unpaused (its data is intact)")
    log(f"backend cache config: {backend_cache_config()}")
    for name, counts in sorted(cache_snapshot().items()):
        total = counts["hit"] + counts["miss"]
        ratio = f"{counts['hit'] / total * 100:.1f}%" if total else "n/a"
        log(f"{name}: {counts['hit']:.0f} hits, {counts['miss']:.0f} misses ({ratio}), "
            f"{counts['errors']:.0f} Redis errors, {counts['bypassed']:.0f} bypassed since backend start")
    if args.action not in ("stop", "pause"):
        log(f"Redis keys: {redis_cli('DBSIZE')}")


# --------------------------------------------------------------------------- Sprint 1: checkout burst

def reset_checkout_state(fixture: dict, users: int, stock: int) -> None:
    """Clears orders, gives the first `users` shoppers one unit of the hot SKU, and sets its stock."""
    hot = fixture["hot_sku"]["id"]
    first = fixture["users"]["first_id"]
    carts = ", ".join(f"('2026-01-01 00:00:00', {hot}, {first + n}, 1)" for n in range(users))
    mysql("SET FOREIGN_KEY_CHECKS = 0; TRUNCATE TABLE orderitems; TRUNCATE TABLE orders; "
          "SET FOREIGN_KEY_CHECKS = 1; "
          f"DELETE FROM cart WHERE user_id BETWEEN {first} AND {first + users - 1}; "
          f"INSERT INTO cart (created_date, product_id, user_id, quantity) VALUES {carts}; "
          f"UPDATE products SET stock = {stock} WHERE id = {hot};", database=DATABASE)


def checkout_invariants(fixture: dict) -> dict:
    hot = fixture["hot_sku"]["id"]
    row = mysql(
        f"SELECT (SELECT stock FROM products WHERE id = {hot}), "
        "(SELECT COUNT(*) FROM orders), "
        "(SELECT COUNT(*) FROM orders WHERE status = 'PENDING'), "
        "(SELECT COUNT(*) FROM orders WHERE session_id IS NOT NULL), "
        "(SELECT COALESCE(SUM(oi.quantity), 0) FROM orderitems oi JOIN orders o ON o.id = oi.order_id "
        f" WHERE o.stock_held = 1 AND oi.product_id = {hot})", database=DATABASE)[0]
    keys = ["stock_after", "orders", "pending_orders", "orders_with_session", "held_units"]
    return dict(zip(keys, (int(v) for v in row)))


def burst_trials(fixture: dict, users: int, stock: int, trials: int, out_dir: Path, timeout: str) -> list[dict]:
    tokens = sign_in(fixture, users, out_dir / "tokens.json")
    expected = min(stock, users)
    results = []
    for trial in range(1, trials + 1):
        trial_dir = out_dir / f"trial-{trial}"
        reset_checkout_state(fixture, users, stock)
        log(f"burst trial {trial}/{trials}: {users} shoppers, stock {stock}")
        before = digest_snapshot()
        env = {"BASE_URL": "http://backend:8080", "USERS": str(users), "REQUEST_TIMEOUT": timeout,
               "KEY_PREFIX": f"burst-{stamp()}-{trial}", "SUMMARY_FILE": "/results/summary.json",
               "K6_NO_USAGE_REPORT": "true"}
        code = run_k6("checkout-burst.js", env, trial_dir, tokens)
        sql = digest_delta(before, digest_snapshot())
        summary_path = trial_dir / "summary.json"
        summary = json.loads(summary_path.read_text()) if summary_path.exists() else {}
        db = checkout_invariants(fixture)
        accepted = metric(summary, "checkout_accepted").get("count", 0)
        rejected = metric(summary, "checkout_rejected").get("count", 0)
        failure_rate = metric(summary, "unexpected_failures").get("rate", 0.0)
        latency = metric(summary, "http_req_duration{route:checkout_create}")
        checks = {
            "accepted == min(stock, shoppers)": accepted == expected,
            "rejected == shoppers - accepted": rejected == users - accepted,
            "no unexpected responses": failure_rate == 0 and code in (0, 99),
            "stock_after == stock - accepted": db["stock_after"] == stock - accepted,
            "stock never negative": db["stock_after"] >= 0,
            "held units == accepted": db["held_units"] == accepted,
            "one PENDING order per acceptance": db["pending_orders"] == accepted == db["orders_with_session"],
            "initial stock == available + held": db["stock_after"] + db["held_units"] == stock,
        }
        result = {"trial": trial, "k6_exit_code": code, "accepted": accepted, "rejected": rejected,
                  "unexpected_failure_rate": failure_rate, "db": db, "checks": checks,
                  "passed": all(checks.values()),
                  "latency_ms": {k: latency.get(k) for k in ("med", "p(95)", "p(99)", "max")},
                  "sql": {k: sql[k] for k in ("all_statements", "app_table_reads", "app_rows_examined")}}
        (trial_dir / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        (trial_dir / "sql-digests.json").write_text(json.dumps(sql, indent=2) + "\n")
        results.append(result)
        log(f"burst trial {trial}: accepted {accepted}, rejected {rejected}, stock {db['stock_after']}, "
            f"held {db['held_units']} -> {'pass' if result['passed'] else 'FAIL'}")
    return results


def cmd_burst(args: argparse.Namespace) -> None:
    fixture = load_fixture_manifest()
    out_dir = RESULTS_DIR / "runs" / f"{stamp()}_burst_{args.users}u_{args.stock}stock"
    manifest = {"command": "python3 benchmarks/bench.py " + shlex.join(sys.argv[1:]), "scenario": "burst",
                "users": args.users, "stock": args.stock, "trials": args.trials, "request_timeout": args.timeout,
                "fixture": fixture, "limits": limits(), "images": image_ids(), "git": git_info(),
                "host": host_info(), "started_at": utc_now()}
    results = burst_trials(fixture, args.users, args.stock, args.trials, out_dir, args.timeout)
    manifest["finished_at"] = utc_now()
    lines = [f"# Checkout burst: {args.users} shoppers, {args.stock} units", "",
             f"Reproduce: `{manifest['command']}`", "",
             f"- Commit `{manifest['git']['commit'][:12]}` on `{manifest['git']['branch']}`"
             + (" with uncommitted changes" if manifest["git"]["dirty"] else ""),
             "- Each shopper sends one POST /order/create-checkout-session for one unit, all at once "
             "(one k6 VU per shopper). Fake payment gateway; Hikari pool 10.",
             "- Pass = every invariant below holds at the database after the burst.", "",
             "| Trial | Result | Accepted | Rejected (409) | Unexpected | Stock after | Held units | p50 ms | p95 ms "
             "| p99 ms |", "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
    for r in results:
        lat = r["latency_ms"]
        lines.append(f"| {r['trial']} | {'pass' if r['passed'] else 'FAIL'} | {r['accepted']} | {r['rejected']} "
                     f"| {r['unexpected_failure_rate'] * 100:.2f}% | {r['db']['stock_after']} | {r['db']['held_units']} "
                     f"| {fmt(lat['med'])} | {fmt(lat['p(95)'])} | {fmt(lat['p(99)'])} |")
    lines += ["", "Invariants checked per trial:", ""]
    lines += [f"- {name}" for name in results[0]["checks"]] if results else []
    lines.append("")
    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (out_dir / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    (out_dir / "report.md").write_text("\n".join(lines))
    print("\n" + "\n".join(lines[7:]) + "\n")
    log(f"report: {(out_dir / 'report.md').relative_to(REPO_DIR)}")
    if not all(r["passed"] for r in results):
        sys.exit(1)


# --------------------------------------------------------------------------- Sprint 1: query plans

CARD_COLUMNS = "id, name, image_url, price_cents, description, category_id, stock"
PRICE_INDEX = "idx_products_category_price_id"


def explain_queries(category: int) -> list[tuple[str, str, str, int]]:
    """(query, variant, SQL, executions). The "with" variants mirror backend CatalogQueries; keep them in sync.

    Baselines: the price queries hide the V6 index with IGNORE INDEX (the plan the V5 schema gets); the
    id-sorted category queries drop the INDEX hint, showing how the optimizer's choice varies with the cursor.
    """
    mid = mysql(f"SELECT price_cents, id FROM products WHERE category_id = {category} "
                "ORDER BY price_cents, id LIMIT 1 OFFSET 400", database=DATABASE)[0]
    price, pid = int(mid[0]), int(mid[1])
    sel = f"SELECT {CARD_COLUMNS} FROM products"
    ignore = f" IGNORE INDEX ({PRICE_INDEX})"
    cat = f"WHERE category_id = {category}"
    price_cursor = f"AND (price_cents > {price} OR (price_cents = {price} AND id > {pid}))"
    rows = []
    for label, tail in (
            ("category page, sort=price_asc, first page", f"{cat} ORDER BY price_cents, id LIMIT 21"),
            ("category page, sort=price_asc, cursor at row 400", f"{cat} {price_cursor} ORDER BY price_cents, id LIMIT 21")):
        rows.append((label, "baseline: V6 index ignored", f"{sel}{ignore} {tail}", 50))
        rows.append((label, "with V6 index", f"{sel} {tail}", 50))
    hint = "/*+ INDEX(products idx_product_category) */ "
    cols = f"{CARD_COLUMNS} FROM products"
    for offset in (None, 8, 400, 780):
        label = "category page, sort=id, " + ("first page" if offset is None else f"cursor at row {offset}")
        after = 0 if offset is None else int(mysql(
            f"SELECT id FROM products WHERE category_id = {category} ORDER BY id LIMIT 1 OFFSET {offset}",
            database=DATABASE)[0][0])
        tail = f"{cat} AND id > {after} ORDER BY id LIMIT 21"
        rows.append((label, "baseline: optimizer's choice", f"SELECT {cols} {tail}", 50))
        rows.append((label, "with INDEX hint (shipped)", f"SELECT {hint}{cols} {tail}", 50))
    rows.append(("catalog page, sort=id, first page", "as shipped", f"{sel} WHERE id > 0 ORDER BY id LIMIT 21", 50))
    rows.append(("legacy GET /product/ (findAll)", "as shipped", sel, 5))
    return rows


def measure_query(sql: str, executions: int) -> dict:
    tree = mysql(f"EXPLAIN FORMAT=TREE {sql}", database=DATABASE)[0][0].replace("\\n", "\n")
    analyze = mysql(f"EXPLAIN ANALYZE {sql}", database=DATABASE)[0][0].replace("\\n", "\n")
    before = digest_snapshot()
    mysql(";".join([sql] * executions) + ";", database=DATABASE)
    delta = digest_delta(before, digest_snapshot())
    stmt = max(delta["statements"], key=lambda st: st["count"])
    return {"executions": stmt["count"], "rows_examined_per_exec": stmt["rows_examined"] / stmt["count"],
            "rows_sent_per_exec": stmt["rows_sent"] / stmt["count"],
            "avg_server_ms": stmt["timer_ps"] / stmt["count"] / 1e9, "plan": tree, "analyze": analyze}


def cmd_explain(args: argparse.Namespace) -> None:
    fixture = load_fixture_manifest()
    out_dir = RESULTS_DIR / "runs" / f"{stamp()}_explain"
    out_dir.mkdir(parents=True, exist_ok=True)
    results = []
    for label, variant, sql, executions in explain_queries(args.category):
        log(f"explain: {label} ({variant})")
        results.append({"query": label, "variant": variant, "sql": sql, **measure_query(sql, executions)})
    git = git_info()
    lines = ["# Catalog query plans", "",
             f"Reproduce: `python3 benchmarks/bench.py explain --category {args.category}`", "",
             f"- Commit `{git['commit'][:12]}` on `{git['branch']}`" + (" with uncommitted changes" if git["dirty"] else ""),
             f"- Fixture v{fixture['fixture_version']}, seed {fixture['seed']}, {fixture['total_products']} products, "
             f"category {args.category}",
             "- Rows examined and server time come from performance_schema statement digests over repeated "
             "executions. Price baselines hide the V6 index with IGNORE INDEX (the plan the V5 schema gets); "
             "id-sort baselines drop the INDEX hint.", "",
             "| Query | Variant | Rows examined / exec | Rows returned | Avg server ms | Access path |",
             "| --- | --- | --- | --- | --- | --- |"]
    for r in results:
        access = next((ln.strip(" ->") for ln in r["plan"].splitlines() if "scan" in ln.lower() or "lookup" in ln.lower()),
                      r["plan"].splitlines()[0].strip(" ->"))
        lines.append(f"| {r['query']} | {r['variant']} | {r['rows_examined_per_exec']:.0f} | {r['rows_sent_per_exec']:.0f} "
                     f"| {r['avg_server_ms']:.3f} | `{access[:90]}` |")
    lines += ["", "## Plans (EXPLAIN ANALYZE)", ""]
    for r in results:
        lines += [f"### {r['query']} ({r['variant']})", "", "```sql", r["sql"], "```", "", "```", r["analyze"], "```", ""]
    (out_dir / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    (out_dir / "report.md").write_text("\n".join(lines))
    print("\n" + "\n".join(lines[8:8 + len(results) + 2]) + "\n")
    log(f"report: {(out_dir / 'report.md').relative_to(REPO_DIR)}")


# --------------------------------------------------------------------------- comparing runs

def run_summary(run_dir: Path) -> dict:
    manifest = json.loads((run_dir / "manifest.json").read_text())
    results = json.loads((run_dir / "results.json").read_text())
    med = lambda values: statistics.median(values) if values else None  # noqa: E731
    pick = lambda key: [r["latency_ms"][key] for r in results if r["latency_ms"].get(key) is not None]  # noqa: E731
    reads = [r["app_sql_reads_per_success"] for r in results if r.get("app_sql_reads_per_success") is not None]
    return {
        "label": f"{manifest['scenario']} @ {manifest['rate']:g}/s ({run_dir.name})",
        "commit": manifest["git"]["commit"][:12],
        "trials passing gate": f"{sum(1 for r in results if r['gate_passed'])}/{len(results)}",
        "median p50 ms": med(pick("med")),
        "median p95 ms": med(pick("p(95)")),
        "median p99 ms": med(pick("p(99)")),
        "median delivered req/s": med([r["delivered_rps"] for r in results]),
        "median successful req/s": med([r["successful_rps"] for r in results]),
        "max unexpected failure %": max(r["unexpected_failure_rate"] for r in results) * 100,
        "total dropped iterations": sum(r["dropped_iterations"] for r in results),
        "median app SQL reads / success": med(reads),
        "median cache hit %": med([r["cache"]["hit_ratio"] * 100 for r in results
                                   if (r.get("cache") or {}).get("hit_ratio") is not None]),
        "total Redis errors": sum((r.get("cache") or {}).get("redis_errors", 0) for r in results),
        "total bypassed reads": sum((r.get("cache") or {}).get("bypassed", 0) for r in results),
        "catalog cache": manifest.get("cache", {}).get("type", "none"),
        "median app rows examined / success": med([r["sql"]["app_rows_examined"] / r["successful_requests"]
                                                   for r in results if r.get("successful_requests")]),
        "avg response bytes": med([r["response_bytes_avg"] for r in results if r.get("response_bytes_avg")]),
        "limits": manifest["limits"],
    }


def cmd_compare(args: argparse.Namespace) -> None:
    dirs = [Path(d) if Path(d).is_absolute() or Path(d).exists() else RESULTS_DIR / "runs" / d for d in args.runs]
    a, b = (run_summary(d) for d in dirs)
    if a["limits"] != b["limits"]:
        log("WARNING: the two runs used different resource limits; the comparison is not controlled")
    lines = [f"| Metric | A: {a['label']} | B: {b['label']} | B vs A |", "| --- | --- | --- | --- |"]
    for key in a:
        if key in ("label", "limits"):
            continue
        va, vb = a[key], b[key]
        change = ""
        if isinstance(va, (int, float)) and isinstance(vb, (int, float)) and va:
            change = f"{(vb - va) / va * 100:+.1f}%"
        show = lambda v: f"{v:,.2f}" if isinstance(v, float) else str(v)  # noqa: E731
        lines.append(f"| {key} | {show(va)} | {show(vb)} | {change} |")
    text = "\n".join(lines) + "\n"
    print("\n" + text)
    if args.out:
        Path(args.out).write_text(text)
        log(f"wrote {args.out}")


# --------------------------------------------------------------------------- CLI

def add_gate_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--p95-ms", type=float, default=300, help="gate: route p95 must stay below this")
    parser.add_argument("--max-failure-rate", type=float, default=0.01, help="gate: unexpected failure rate")
    parser.add_argument("--timeout", default="2s", help="k6 request timeout")
    parser.add_argument("--trace-seed", type=int, default=1, help="seed for the request trace")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    up = sub.add_parser("up", help="build and start the bench stack")
    up.add_argument("--observability", action="store_true", help="also start Prometheus and Grafana")
    up.add_argument("--cache", choices=["none", "redis"],
                    help="catalog cache for the backend (default: $BENCH_CACHE or none); recreates the backend")
    up.add_argument("--search", action="store_true",
                    help="also start Elasticsearch and enable the search engine and indexer (default: $BENCH_SEARCH)")
    up.add_argument("--events", action="store_true",
                    help="also start RocketMQ and publish the catalog outbox to the search and cache consumers; "
                         "implies --search and --cache redis (default: $BENCH_EVENTS)")
    up.set_defaults(func=cmd_up)

    down = sub.add_parser("down", help="stop the bench stack")
    down.add_argument("--volumes", action="store_true", help="also delete the bench database volume")
    down.set_defaults(func=cmd_down)

    seed = sub.add_parser("seed", help="generate and load the deterministic fixture")
    seed.add_argument("--seed", type=int, default=20261109)
    seed.add_argument("--products", type=int, default=10_000)
    seed.add_argument("--users", type=int, default=200)
    seed.add_argument("--cart-users", type=int, default=100)
    seed.add_argument("--hot-sku-stock", type=int, default=20)
    seed.set_defaults(func=cmd_seed)

    seed_wands = sub.add_parser("seed-wands", help="load the WANDS catalog for search relevance and load runs")
    seed_wands.add_argument("--split-seed", type=int, default=20261109,
                            help="seed for the tuning/held-out query split; fix it before looking at results")
    seed_wands.set_defaults(func=cmd_seed_wands)

    lag = sub.add_parser("search-lag", help="time from an admin edit to the first search response that reflects it")
    lag.add_argument("--samples", type=int, default=50, help="edits to time, one at a time")
    lag.add_argument("--interval-ms", type=int, default=50, help="search probe interval")
    lag.add_argument("--pause-ms", type=int, default=200, help="gap between samples")
    lag.add_argument("--outage", type=float, default=0,
                     help="also pause Elasticsearch for this many seconds while editing, and time the catch-up")
    lag.set_defaults(func=cmd_search_lag)

    events = sub.add_parser("events", help="catalog event pipeline: load, reconcile, faults, replay (Sprint 5)")
    events_sub = events.add_subparsers(dest="events_command", required=True)
    ev_run = events_sub.add_parser("run", help="admin edits at a fixed rate, then drain and reconcile every event")
    ev_run.add_argument("--rate", type=float, default=50, help="offered admin edits/sec (one event each)")
    ev_run.add_argument("--warmup", default="30s")
    ev_run.add_argument("--duration", default="5m")
    ev_run.add_argument("--fault", choices=sorted(FAULTS), help="inject one fault during the measured window")
    ev_run.add_argument("--fault-at", type=float, default=60, help="seconds into the measured window")
    ev_run.add_argument("--fault-for", type=float, default=30, help="downtime for stop-* faults")
    ev_run.add_argument("--drain-timeout", type=float, default=300, help="seconds to wait for the backlog to clear")
    add_gate_options(ev_run)
    ev_run.set_defaults(func=cmd_events_run)
    ev_replay = events_sub.add_parser("replay", help="re-publish a run's unapplied events under their original IDs")
    ev_replay.add_argument("run", help="run directory or its name under results/events")
    ev_replay.add_argument("--group", choices=EVENT_GROUPS, help="only events this group never applied")
    ev_replay.add_argument("--all", action="store_true",
                           help="re-publish every event in the run (a producer crash window; all must be skipped)")
    ev_replay.add_argument("--drain-timeout", type=float, default=300)
    ev_replay.set_defaults(func=cmd_events_replay)

    for name, func, help_text in (("run", cmd_run, "warm-up plus measured trials at one rate"),
                                  ("ladder", cmd_ladder, "step the rate until the gate fails")):
        p = sub.add_parser(name, help=help_text)
        p.add_argument("scenario", choices=sorted(SCENARIOS))
        p.add_argument("--pattern", choices=["uniform", "hot"], default="uniform",
                       help="detail access pattern (hot = 80%% of reads on 20%% of products)")
        p.add_argument("--sort", choices=["id", "price_asc"], default="id", help="page scenario sort")
        p.add_argument("--size", type=int, default=20, help="page and search scenario page size")
        p.add_argument("--engine", choices=["mysql", "elasticsearch"], default="elasticsearch",
                       help="search scenario engine")
        p.add_argument("--env", action="append", metavar="KEY=VALUE", help="extra k6 env var")
        p.add_argument("--flush-cache", action="store_true",
                       help="empty Redis before each trial (with --warmup 0s this measures a cold cache)")
        add_gate_options(p)
        if name == "run":
            p.add_argument("--rate", type=float, help="offered requests/sec")
            p.add_argument("--trials", type=int, default=3)
            p.add_argument("--warmup", default="2m")
            p.add_argument("--duration", default="5m")
        else:
            p.add_argument("--rates", default="40,200,500,1000,2000", help="comma-separated offered rates")
            p.add_argument("--warmup", default="30s")
            p.add_argument("--duration", default="1m")
            p.add_argument("--keep-going", action="store_true", help="continue after the first failing step")
        p.set_defaults(func=func)

    burst = sub.add_parser("burst", help="checkout contention burst with database invariant checks")
    burst.add_argument("--users", type=int, default=100, help="shoppers, one request each, all at once")
    burst.add_argument("--stock", type=int, default=20, help="units of the hot SKU before each trial")
    burst.add_argument("--trials", type=int, default=3)
    burst.add_argument("--timeout", default="10s", help="k6 request timeout")
    burst.set_defaults(func=cmd_burst)

    explain = sub.add_parser("explain", help="query plans and rows examined for the catalog queries")
    explain.add_argument("--category", type=int, default=1)
    explain.set_defaults(func=cmd_explain)

    compare = sub.add_parser("compare", help="side-by-side summary of two run directories")
    compare.add_argument("runs", nargs=2, metavar="RUN", help="run directory or its name under results/runs")
    compare.add_argument("--out", help="also write the table to this file")
    compare.set_defaults(func=cmd_compare)

    cache = sub.add_parser("cache", help="Redis cache counters, flush, or outage injection")
    cache.add_argument("action", choices=["stats", "flush", "stop", "start", "pause", "unpause"])
    cache.set_defaults(func=cmd_cache)

    smoke = sub.add_parser("smoke", help="short end-to-end check, including a negative check")
    add_gate_options(smoke)
    smoke.set_defaults(func=cmd_smoke)

    args = parser.parse_args()
    if sys.platform == "darwin" and args.command in ("run", "ladder", "smoke", "burst", "events"):
        # Keep macOS from idle-sleeping mid-run; it exits when this process does.
        # Closing the lid on battery can still sleep the machine, so stay plugged in.
        subprocess.Popen(["caffeinate", "-i", "-w", str(os.getpid())])
    args.func(args)


if __name__ == "__main__":
    main()
