#!/usr/bin/env python3
"""ShopSphere benchmark runner (Sprints 0-2). Standard library only.

  python3 benchmarks/bench.py up [--observability] [--cache redis]   build and start the bench stack
  python3 benchmarks/bench.py seed [--products 10000]  load the deterministic fixture
  python3 benchmarks/bench.py run detail --rate 40 --pattern hot --trials 3
  python3 benchmarks/bench.py run page --rate 40 --sort price_asc
  python3 benchmarks/bench.py ladder detail --rates 40,200,500,1000,2000
  python3 benchmarks/bench.py burst --users 100 --stock 20 --trials 3   checkout contention + invariants
  python3 benchmarks/bench.py explain                  catalog query plans and rows examined
  python3 benchmarks/bench.py compare RUN_A RUN_B      side-by-side summary of two runs
  python3 benchmarks/bench.py cache stats|flush|stop|start|pause|unpause   Redis cache and outages (Sprint 2)
  python3 benchmarks/bench.py smoke                    short end-to-end check (used by CI)
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
REDIS_IMAGE = "redis:7.4.2-alpine"
MYSQL_IMAGE = "mysql:8.0.43"
BACKEND_IMAGE = os.environ.get(
    "BENCH_BACKEND_IMAGE", "shopsphere-backend:bench" if PROJECT == "shopsphere-bench" else f"shopsphere-backend:{PROJECT}")
os.environ.setdefault("BENCH_BACKEND_IMAGE", BACKEND_IMAGE)
DATABASE = "shopsphere_bench"
MYSQL_ROOT_PASSWORD = "bench-root"
RESULTS_DIR = BENCH_DIR / "results"
FIXTURE_DIR = RESULTS_DIR / "fixture"
GENERATOR = BENCH_DIR / "seed" / "generate_fixture.py"

APP_TABLES = "products|categories|users|cart|orders|orderitems|wishlist|processed_stripe_events"
APP_READ_RE = re.compile(rf"^SELECT\b.*?\bFROM\s+`?({APP_TABLES})`?(\s|$)", re.IGNORECASE | re.DOTALL)

SCENARIOS = {
    "detail": {"script": "product-detail.js", "route": "product_detail", "rate": 40, "cache": "productDetail"},
    "list": {"script": "product-list.js", "route": "product_list", "rate": 2},
    "cart": {"script": "checkout-skeleton.js", "route": "cart_read", "rate": 20, "tokens": True},
    "page": {"script": "product-page.js", "route": "product_page", "rate": 40, "cache": "catalogPage"},
}

LIMIT_DEFAULTS = {
    "BENCH_BACKEND_CPUS": "2",
    "BENCH_BACKEND_MEM": "1536m",
    "BENCH_MYSQL_CPUS": "2",
    "BENCH_MYSQL_MEM": "1g",
    "BENCH_K6_CPUS": "2",
    "BENCH_REDIS_CPUS": "1",
    "BENCH_REDIS_MEM": "256m",
}


# --------------------------------------------------------------------------- helpers

def log(message: str) -> None:
    print(f"[bench] {message}", flush=True)


def sh(cmd: list[str], *, check: bool = True, capture: bool = False, stdin=None) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, check=check, text=True, stdin=stdin,
                          stdout=subprocess.PIPE if capture else None)


def compose(*args: str, observability: bool = False, **kwargs) -> subprocess.CompletedProcess:
    cmd = ["docker", "compose", "-p", PROJECT, "-f", str(COMPOSE_FILE)]
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
    for image in (BACKEND_IMAGE, MYSQL_IMAGE, K6_IMAGE, REDIS_IMAGE):
        out = sh(["docker", "image", "inspect", "--format", "{{.Id}}", image], capture=True, check=False)
        ids[image] = out.stdout.strip() or None
    backend = compose("ps", "-q", "backend", capture=True).stdout.strip()
    if backend:
        ids[BACKEND_IMAGE] = sh(["docker", "inspect", "--format", "{{.Image}}", backend],
                                capture=True).stdout.strip()
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
    fields = {"catalog_cache_errors_total": "errors", "catalog_cache_bypassed_total": "bypassed"}
    for line in out.splitlines():
        match = PROM_LINE_RE.match(line)
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
        entry = caches.setdefault(name, dict.fromkeys(("hit", "miss", "errors", "bypassed", "bypassed_gets"), 0.0))
        count = float(match.group(3))
        entry[field] = entry.get(field, 0.0) + count
        if field == "bypassed" and labels.get("operation") == "get":
            entry["bypassed_gets"] = entry.get("bypassed_gets", 0.0) + count
    return caches


def cache_delta(before: dict, after: dict, cache: str | None) -> dict | None:
    if cache is None or cache not in after:
        return None
    prev, current = before.get(cache, {}), after[cache]
    hits, misses, errors, bypassed, bypassed_gets = (
        int(current.get(key, 0) - prev.get(key, 0))
        for key in ("hit", "miss", "errors", "bypassed", "bypassed_gets"))
    return {"cache": cache, "hits": hits, "misses": misses, "redis_errors": errors,
            "bypassed": bypassed, "bypassed_gets": bypassed_gets,
            "hit_ratio": hits / (hits + misses) if hits + misses else None}


def redis_cli(*args: str) -> str:
    return compose("exec", "-T", "redis", "redis-cli", *args, capture=True).stdout.strip()


def flush_cache() -> None:
    redis_cli("FLUSHALL")


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
        "PRODUCT_COUNT": str(fixture["catalog_products"]),
        "TOTAL_PRODUCTS": str(fixture["total_products"]),
        "HOT_SKU_ID": str(fixture["hot_sku"]["id"]),
        "CART_USERS": str(fixture["carts_with_hot_sku"]),
        "FIRST_USER_ID": str(fixture["users"]["first_id"]),
        "BENCH_PASSWORD": fixture["users"]["password"],
        "K6_NO_USAGE_REPORT": "true",
    }
    if scenario == "detail":
        env["PATTERN"] = args.pattern
    if scenario == "page":
        env["SORT"] = args.sort
        env["SIZE"] = str(args.size)
        env["CATEGORIES"] = str(fixture["categories"])
    for item in getattr(args, "env", None) or []:
        key, _, value = item.partition("=")
        env[key] = value
    return env


def run_k6(script: str, env: dict[str, str], results_dir: Path, tokens: Path | None = None) -> int:
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
        # Split by the backend's X-Cache header; null when the route or the cache-off variant sends none.
        "latency_by_cache": {
            outcome: ({k: values.get(k) for k in ("med", "p(90)", "p(95)", "p(99)", "max", "count")}
                      if (values := metric(summary, f"cache_{outcome}_duration")) else None)
            for outcome in ("hit", "miss")},
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
    for trial in range(1, trials + 1):
        trial_dir = out_dir / f"trial-{trial}"
        if getattr(args, "flush_cache", False):
            log(f"trial {trial}: flushing Redis, so the trial starts from an empty cache")
            flush_cache()
        if duration_seconds(warmup) > 0:
            log(f"{scenario} @ {rate}/s trial {trial}/{trials}: warm-up {warmup}")
            env = k6_env(scenario, args, fixture, rate, warmup) | {
                "WARMUP": "1", "SUMMARY_FILE": "/results/warmup-summary.json"}
            run_k6(spec["script"], env, trial_dir, tokens)
        log(f"{scenario} @ {rate}/s trial {trial}/{trials}: measuring {duration}")
        before = digest_snapshot()
        cache_before = cache_snapshot()
        started = utc_now()
        env = k6_env(scenario, args, fixture, rate, duration) | {"SUMMARY_FILE": "/results/summary.json"}
        code = run_k6(spec["script"], env, trial_dir, tokens)
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
        "| Unexpected failures | Dropped | App SQL reads / success | Cache hit % | Hit p50 / p95 ms "
        "| Miss p50 / p95 ms | Redis errors | Bypassed cache operations | Avg response bytes |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for r in results:
        lat = r["latency_ms"]
        gate = "INVALID" if r.get("invalid") else ("pass" if r["gate_passed"] else "FAIL")
        lines.append(
            f"| {r['trial']} | {gate} | {r['scheduled_iterations']} "
            f"| {fmt(r['delivered_rps'])} | {fmt(r['successful_rps'])} | {fmt(lat['med'])} "
            f"| {fmt(lat['p(95)'])} | {fmt(lat['p(99)'])} | {fmt(r['unexpected_failure_rate'] * 100, 2)}% "
            f"| {r['dropped_iterations']} | {fmt(r['app_sql_reads_per_success'], 2)} "
            f"| {cache_hit_pct(r)} | {split_latency(r, 'hit')} | {split_latency(r, 'miss')} "
            f"| {(r.get('cache') or {}).get('redis_errors', 'n/a')} "
            f"| {(r.get('cache') or {}).get('bypassed', 'n/a')} "
            f"| {fmt(r['response_bytes_avg'], 0)} |")
    return lines


def split_latency(result: dict, outcome: str) -> str:
    lat = (result.get("latency_by_cache") or {}).get(outcome)
    return "n/a" if not lat else f"{fmt(lat['med'], 2)} / {fmt(lat['p(95)'])}"


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
        f"- Fixture v{fixture['fixture_version']}, seed {fixture['seed']}, {fixture['catalog_products']} catalog "
        f"products, sha256 `{fixture['sql_sha256'][:12]}`",
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
        "size": args.size if scenario == "page" else None,
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

def cmd_up(args: argparse.Namespace) -> None:
    cache = getattr(args, "cache", None) or os.environ.get("BENCH_CACHE", "none")
    os.environ["BENCH_CACHE"] = cache
    services = ["mysql", "redis", "backend"] + (["prometheus", "grafana"] if args.observability else [])
    build_flag = "--no-build" if getattr(args, "no_build", False) else "--build"
    compose("up", "-d", build_flag, "--wait", *services, observability=args.observability)
    log(f"stack {PROJECT}: backend on http://127.0.0.1:{BACKEND_PORT} (actuator stays on the internal port 8081), "
        f"catalog cache {backend_cache_config()['type']}")
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


def cmd_run(args: argparse.Namespace) -> None:
    fixture = load_fixture_manifest()
    rate = args.rate if args.rate is not None else SCENARIOS[args.scenario]["rate"]
    label = (f"{args.scenario}_{rate:g}rps" + (f"_{args.pattern}" if args.scenario == "detail" else "")
             + (f"_{args.sort}" if args.scenario == "page" else ""))
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
    rates = [float(r) for r in args.rates.split(",")]
    label = f"ladder_{args.scenario}" + (f"_{args.pattern}" if args.scenario == "detail" else "")
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
            f"{counts['errors']:.0f} Redis errors, {counts['bypassed']:.0f} bypassed cache operations "
            f"({counts['bypassed_gets']:.0f} gets) since backend start")
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
    def split(outcome: str, key: str) -> list[float]:
        return [lat[key] for r in results
                if (lat := (r.get("latency_by_cache") or {}).get(outcome)) and lat.get(key) is not None]

    reads = [r["app_sql_reads_per_success"] for r in results if r.get("app_sql_reads_per_success") is not None]
    return {
        "label": f"{manifest['scenario']} @ {manifest['rate']:g}/s ({run_dir.name})",
        "commit": manifest["git"]["commit"][:12],
        "trials passing gate": f"{sum(1 for r in results if r['gate_passed'])}/{len(results)}",
        "median p50 ms": med(pick("med")),
        "median p95 ms": med(pick("p(95)")),
        "median p99 ms": med(pick("p(99)")),
        "median hit p50 ms": med(split("hit", "med")),
        "median hit p95 ms": med(split("hit", "p(95)")),
        "median miss p50 ms": med(split("miss", "med")),
        "median miss p95 ms": med(split("miss", "p(95)")),
        "median delivered req/s": med([r["delivered_rps"] for r in results]),
        "median successful req/s": med([r["successful_rps"] for r in results]),
        "max unexpected failure %": max(r["unexpected_failure_rate"] for r in results) * 100,
        "total dropped iterations": sum(r["dropped_iterations"] for r in results),
        "median app SQL reads / success": med(reads),
        "median cache hit %": med([r["cache"]["hit_ratio"] * 100 for r in results
                                   if (r.get("cache") or {}).get("hit_ratio") is not None]),
        "total Redis errors": sum((r.get("cache") or {}).get("redis_errors", 0) for r in results),
        "total bypassed cache operations": sum((r.get("cache") or {}).get("bypassed", 0) for r in results),
        "total bypassed gets": sum((r.get("cache") or {}).get("bypassed_gets", 0) for r in results)
        if all("bypassed_gets" in (r.get("cache") or {}) or not (r.get("cache") or {}).get("bypassed")
               for r in results) else None,
        "catalog cache": manifest.get("cache", {}).get("type", "none"),
        "median app rows examined / success": med([r["sql"]["app_rows_examined"] / r["successful_requests"]
                                                   for r in results if r.get("successful_requests")]),
        "avg response bytes": med([r["response_bytes_avg"] for r in results if r.get("response_bytes_avg")]),
        "limits": manifest["limits"],
    }


def comparison_issues(a: dict, b: dict) -> list[str]:
    fields = ("scenario", "rate", "pattern", "sort", "size", "warmup", "duration", "trace_seed",
              "request_timeout", "gate", "extra_env", "limits", "flush_cache")
    issues = [field for field in fields if a.get(field) != b.get(field)]
    for name, path in (
        ("fixture SQL hash", ("fixture", "sql_sha256")),
        ("commit", ("git", "commit")),
    ):
        key, field = path
        if a.get(key, {}).get(field) != b.get(key, {}).get(field):
            issues.append(name)
    if a.get("git", {}).get("dirty") or b.get("git", {}).get("dirty"):
        issues.append("dirty working tree")
    if a.get("images") != b.get("images"):
        issues.append("container images")
    return issues


def cmd_compare(args: argparse.Namespace) -> None:
    dirs = [Path(d) if Path(d).is_absolute() or Path(d).exists() else RESULTS_DIR / "runs" / d for d in args.runs]
    a, b = (run_summary(d) for d in dirs)
    manifests = [json.loads((directory / "manifest.json").read_text()) for directory in dirs]
    issues = comparison_issues(*manifests)
    if issues:
        log(f"WARNING: comparison setup differs or is unverified ({', '.join(issues)})")
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
    up.add_argument("--no-build", action="store_true", help="reuse the existing backend image")
    up.add_argument("--cache", choices=["none", "redis"],
                    help="catalog cache for the backend (default: $BENCH_CACHE or none); recreates the backend")
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

    for name, func, help_text in (("run", cmd_run, "warm-up plus measured trials at one rate"),
                                  ("ladder", cmd_ladder, "step the rate until the gate fails")):
        p = sub.add_parser(name, help=help_text)
        p.add_argument("scenario", choices=sorted(SCENARIOS))
        p.add_argument("--pattern", choices=["uniform", "hot"], default="uniform",
                       help="detail access pattern (hot = 80%% of reads on 20%% of products)")
        p.add_argument("--sort", choices=["id", "price_asc"], default="id", help="page scenario sort")
        p.add_argument("--size", type=int, default=20, help="page scenario page size")
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
    if sys.platform == "darwin" and args.command in ("run", "ladder", "smoke", "burst"):
        # Keep macOS from idle-sleeping mid-run; it exits when this process does.
        # Closing the lid on battery can still sleep the machine, so stay plugged in.
        subprocess.Popen(["caffeinate", "-i", "-w", str(os.getpid())])
    args.func(args)


if __name__ == "__main__":
    main()
