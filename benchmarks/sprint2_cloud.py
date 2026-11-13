#!/usr/bin/env python3
"""Run matched Sprint 2 trials and collect evidence on a dedicated cloud runner."""

import argparse
import json
import os
import sys
from urllib.parse import urlencode
from urllib.request import urlopen

import bench


def cli(*args, check=True):
    return bench.sh([sys.executable, str(bench.BENCH_DIR / "bench.py"), *args], check=check)


def switch_cache(mode):
    cli("up", "--no-build", "--observability", "--cache", mode)


def save_telemetry(directory, results):
    port = os.environ.get("BENCH_PROMETHEUS_PORT", "9090")
    queries = {
        "pool_active": 'hikaricp_connections_active{pool="shopsphere"}',
        "pool_pending": 'hikaricp_connections_pending{pool="shopsphere"}',
        "pool_max": 'hikaricp_connections_max{pool="shopsphere"}',
        "detail_misses_per_second": 'rate(cache_gets_total{cache="productDetail",result="miss"}[15s])',
        "redis_errors_per_second": 'sum by (operation) (rate(catalog_cache_errors_total[15s]))',
        "gc_pause_max_seconds": "jvm_gc_pause_seconds_max",
    }
    series = {}
    for name, query in queries.items():
        params = urlencode({
            "query": query,
            "start": results[0]["measured_window"]["started_at"],
            "end": results[-1]["measured_window"]["finished_at"],
            "step": 5,
        })
        with urlopen(f"http://127.0.0.1:{port}/api/v1/query_range?{params}", timeout=15) as response:
            series[name] = json.load(response)["data"]["result"]
    (directory / "telemetry.json").write_text(json.dumps(series) + "\n")
    peaks = {
        name: max((float(value) for line in lines for _, value in line["values"]), default=None)
        for name, lines in series.items()
    }
    print(f"CLOUD_TELEMETRY {directory.name} {json.dumps(peaks)}", flush=True)


def measure(label, command, route, *options):
    runs = bench.RESULTS_DIR / "runs"
    before = set(runs.glob("*"))
    cli(command, route, *options, check=False)
    directory, = set(runs.glob("*")) - before
    manifest = json.loads((directory / "manifest.json").read_text())
    result_name = "ladder.json" if command == "ladder" else "results.json"
    results = json.loads((directory / result_name).read_text())
    (directory / "cloud-label.txt").write_text(label + "\n")
    summary = {
        "label": label,
        "cache": manifest["cache"],
        "fixture": manifest["fixture"]["sql_sha256"],
        "images": manifest["images"],
        "git": manifest["git"],
        "host": manifest["host"],
        "trials": results,
    }
    print(f"CLOUD_RESULT {json.dumps(summary)}", flush=True)
    save_telemetry(directory, results)
    return directory, manifest, results


def paired(route, block, rate):
    modes = ("redis", "none") if block == 2 else ("none", "redis")
    runs = {}
    for mode in modes:
        switch_cache(mode)
        runs[mode] = measure(
            f"{route}-{block}-{mode}", "run", route, "--rate", rate, "--pattern", "hot",
            "--trials", "1", "--warmup", "2m", "--duration", "5m",
        )
    off, on = runs["none"], runs["redis"]
    issues = bench.comparison_issues(off[1], on[1])
    if issues:
        raise RuntimeError(f"Cloud A/B controls differ: {', '.join(issues)}")
    cli("compare", str(off[0]), str(on[0]))


def annotate(label, result):
    """One GitHub notice per run, readable through the public check-run annotations API."""
    lat = result["latency_ms"]
    split = result.get("latency_by_cache") or {}
    cache = result.get("cache") or {}

    def ms(value):
        return "n/a" if value is None else f"{value:.3f}"

    parts = [
        f"gate={'pass' if result['gate_passed'] else 'FAIL'}",
        f"drops={result['dropped_iterations']}",
        f"fail%={result['unexpected_failure_rate'] * 100:.2f}",
        f"p50={ms(lat['med'])}", f"p95={ms(lat['p(95)'])}", f"p99={ms(lat['p(99)'])}",
        f"sql/ok={ms(result.get('app_sql_reads_per_success'))}",
        f"hit%={ms((cache.get('hit_ratio') or 0) * 100) if cache.get('hit_ratio') is not None else 'n/a'}",
    ]
    for outcome in ("hit", "miss"):
        values = split.get(outcome)
        if values:
            parts.append(f"{outcome}: n={values['count']} p50={ms(values['med'])} p95={ms(values['p(95)'])} "
                         f"p99={ms(values['p(99)'])}")
    print(f"::notice title={label}::{' | '.join(parts)}", flush=True)


def split_by_outcome(block, rate):
    """Cache off, cache on with a 60 s TTL and with a 300 s TTL, with hit and miss latency measured apart."""
    variants = [("none", "60s"), ("redis", "60s"), ("redis", "300s")]
    shift = (block - 1) % len(variants)
    for mode, ttl in variants[shift:] + variants[:shift]:
        os.environ["BENCH_CACHE_TTL"] = ttl
        switch_cache(mode)
        label = f"split-{rate}-{block}-" + ("off" if mode == "none" else f"ttl{ttl}")
        # A 5 m warm-up lets the 300 s TTL reach its steady hit ratio before the measured window.
        _, _, results = measure(label, "run", "detail", "--rate", rate, "--pattern", "hot",
                                "--trials", "1", "--warmup", "5m", "--duration", "5m")
        annotate(label, results[0])


def ladder(rates):
    for mode in ("none", "redis"):
        switch_cache(mode)
        measure(f"detail-ladder-{mode}", "ladder", "detail", "--pattern", "hot",
                "--rates", rates)


def cold_and_uniform():
    for mode in ("none", "redis"):
        switch_cache(mode)
        measure(f"uniform-{mode}", "run", "detail", "--rate", "500", "--pattern", "uniform", "--trials", "1")
    # The preceding uniform trial warmed the JVM and MySQL. Flush just Redis for this run.
    measure("cold-hot", "run", "detail", "--rate", "500", "--pattern", "hot",
            "--trials", "1", "--flush-cache", "--warmup", "0s")


def outage():
    rate = "250"
    def run(label, warmup="0s", duration="1m"):
        return measure(label, "run", "detail", "--rate", rate, "--pattern", "hot",
                       "--trials", "1", "--warmup", warmup, "--duration", duration)[2][0]

    if not run("outage-mysql-baseline", "2m")["gate_passed"]:
        raise RuntimeError(f"MySQL baseline could not sustain {rate} req/s")
    switch_cache("redis")
    run("outage-warm-redis", "2m")
    cli("cache", "stop")
    try:
        run("outage-stopped")
    finally:
        cli("cache", "start")
    run("outage-restart-recovery", duration="2m")
    cli("cache", "pause")
    try:
        run("outage-paused")
    finally:
        cli("cache", "unpause")
    run("outage-unpause-recovery", duration="2m")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("scenario", choices=("page", "detail", "ladder", "cold", "outage", "split"))
    parser.add_argument("--block", type=int, choices=(1, 2, 3), default=1)
    parser.add_argument("--rate", default="500")
    parser.add_argument("--rates", default="250,500,750,1000,1250,1500")
    args = parser.parse_args()

    bench.sh(["docker", "pull", bench.K6_IMAGE])
    cli("up", "--observability", "--cache", "none")
    cli("seed")
    if args.scenario in ("page", "detail"):
        paired(args.scenario, args.block, args.rate)
    elif args.scenario == "split":
        split_by_outcome(args.block, args.rate)
    elif args.scenario == "ladder":
        ladder(args.rates)
    else:
        {"cold": cold_and_uniform, "outage": outage}[args.scenario]()


if __name__ == "__main__":
    main()
