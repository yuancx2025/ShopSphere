import argparse
import copy
import unittest
from unittest.mock import patch

import bench


class BenchmarkEvidenceTest(unittest.TestCase):
    def test_counts_bypassed_reads_separately_from_cache_operations(self):
        metrics = """
cache_gets_total{cache="productDetail",result="hit"} 75
cache_gets_total{cache="productDetail",result="miss"} 25
catalog_cache_errors_total{cache="productDetail",operation="get"} 1
catalog_cache_bypassed_total{cache="productDetail",operation="get"} 20
catalog_cache_bypassed_total{cache="productDetail",operation="put"} 21
"""
        with patch.object(bench, "compose", return_value=argparse.Namespace(stdout=metrics)):
            after = bench.cache_snapshot()
        result = bench.cache_delta({}, after, "productDetail")
        self.assertEqual(result["bypassed"], 41)
        self.assertEqual(result["bypassed_gets"], 20)
        self.assertEqual(result["redis_errors"], 1)
        self.assertEqual(result["hit_ratio"], 0.75)

    def test_comparison_checks_controls_but_allows_the_cache_flag_to_differ(self):
        off = {
            "scenario": "detail",
            "rate": 500,
            "trace_seed": 1,
            "fixture": {"sql_sha256": "seeded"},
            "git": {"commit": "clean", "dirty": False},
            "images": {"backend": "one-image"},
            "cache": {"type": "none"},
        }
        on = copy.deepcopy(off)
        on["cache"]["type"] = "redis"
        self.assertEqual(bench.comparison_issues(off, on), [])

        on["rate"] = 750
        on["trace_seed"] = 2
        on["fixture"]["sql_sha256"] = "different"
        on["git"]["dirty"] = True
        on["images"]["backend"] = "rebuilt"
        self.assertEqual(
            bench.comparison_issues(off, on),
            ["rate", "trace_seed", "fixture SQL hash", "dirty working tree", "container images"],
        )


if __name__ == "__main__":
    unittest.main()
