#!/usr/bin/env python3
"""Deterministic benchmark fixture for ShopSphere.

Writes ``fixture.sql`` and ``manifest.json`` to ``--out-dir``. The SQL resets the
catalog, carts and orders of the *benchmark* database and inserts:

* ``--categories`` categories (ids 1..C)
* ``--products`` catalog products (ids 1..N), spread round-robin over categories
* one hot SKU (id N+1) with ``--hot-sku-stock`` units, kept out of catalog traces
* ``--users`` shopper accounts (ids 1001..), all with the password in the manifest
* a one-unit cart of the hot SKU for the first ``--cart-users`` shoppers

Only ``random.Random.random()`` is used, because Python guarantees that its
sequence stays the same across versions for a given seed. Same seed and same
arguments produce a byte-identical fixture.

Standard library only. Run it with ``python3 -I``.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import random
from pathlib import Path

# v2 (Sprint 1): products carry price_cents.
FIXTURE_VERSION = 2

# bcrypt (cost 10) of BENCH_PASSWORD. Test-only credential for the benchmark DB.
BENCH_PASSWORD = "bench-password-1"
BENCH_PASSWORD_HASH = "$2a$10$A1zDzXSde3EKQDNxsJ3zX.DhZSzKphT9bbhzvMevrau/cumkCUS7G"
FIRST_USER_ID = 1001
REGULAR_STOCK = 1000
FIXED_TIMESTAMP = "2026-01-01 00:00:00"
BATCH_SIZE = 1000

CATEGORY_NAMES = [
    ("Electronics", ["Headphones", "Speaker", "Charger", "Keyboard", "Monitor", "Webcam"]),
    ("Clothing", ["Jacket", "Hoodie", "T-Shirt", "Sweater", "Raincoat", "Vest"]),
    ("Books", ["Notebook", "Cookbook", "Atlas", "Journal", "Field Guide", "Novel"]),
    ("Home & Garden", ["Planter", "Lamp", "Rug", "Cushion", "Vase", "Shelf"]),
    ("Kitchen", ["Skillet", "Kettle", "Knife Set", "Mixing Bowl", "Cutting Board", "Teapot"]),
    ("Sports", ["Yoga Mat", "Water Bottle", "Dumbbell", "Jump Rope", "Backpack", "Tent"]),
    ("Toys", ["Puzzle", "Building Set", "Plush Bear", "Kite", "Board Game", "Robot Kit"]),
    ("Beauty", ["Face Cream", "Hair Brush", "Mirror", "Soap Bar", "Lip Balm", "Diffuser"]),
    ("Office", ["Desk Organizer", "Pen Set", "Stapler", "Desk Lamp", "Planner", "Chair Mat"]),
    ("Pets", ["Dog Bed", "Cat Tree", "Leash", "Pet Bowl", "Chew Toy", "Grooming Kit"]),
    ("Automotive", ["Seat Cover", "Phone Mount", "Floor Mat", "Tire Gauge", "Car Vacuum", "Sunshade"]),
    ("Music", ["Guitar Strap", "Ukulele", "Drum Pad", "Capo", "Music Stand", "Metronome"]),
]
ADJECTIVES = [
    "Classic", "Compact", "Deluxe", "Everyday", "Premium", "Rugged", "Slim", "Smart",
    "Vintage", "Modern", "Portable", "Ergonomic", "Eco", "Heavy-Duty", "Lightweight",
    "Minimalist", "Pro", "Essential", "Urban", "Outdoor",
]
MATERIALS = [
    "Bamboo", "Cotton", "Steel", "Oak", "Ceramic", "Leather", "Wool", "Glass",
    "Aluminum", "Linen", "Walnut", "Silicone", "Canvas", "Copper", "Recycled",
]


class Rng:
    """Draws everything from ``random()`` so the sequence is version-stable."""

    def __init__(self, seed: int) -> None:
        self._r = random.Random(seed)

    def unit(self) -> float:
        return self._r.random()

    def int_between(self, lo: int, hi: int) -> int:
        return lo + int(self.unit() * (hi - lo + 1))

    def pick(self, items):
        return items[int(self.unit() * len(items))]


def sql_str(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def batched_insert(table: str, columns: list[str], rows: list[list[str]]) -> list[str]:
    statements = []
    for start in range(0, len(rows), BATCH_SIZE):
        chunk = rows[start:start + BATCH_SIZE]
        values = ",\n".join("(" + ", ".join(row) + ")" for row in chunk)
        statements.append(f"INSERT INTO {table} ({', '.join(columns)}) VALUES\n{values};")
    return statements


def build(args: argparse.Namespace) -> tuple[str, dict]:
    rng = Rng(args.seed)
    categories = CATEGORY_NAMES[: args.categories]
    if len(categories) < args.categories:
        raise SystemExit(f"at most {len(CATEGORY_NAMES)} categories are defined")

    category_rows = []
    for cid, (name, _) in enumerate(categories, start=1):
        category_rows.append([
            str(cid),
            sql_str(name),
            sql_str(f"Benchmark category: {name}"),
            sql_str(f"https://images.shopsphere.test/categories/{cid}.jpg"),
        ])

    product_rows = []
    for pid in range(1, args.products + 1):
        cid = (pid - 1) % len(categories) + 1
        noun = rng.pick(categories[cid - 1][1])
        adjective = rng.pick(ADJECTIVES)
        material = rng.pick(MATERIALS)
        model = f"{chr(65 + rng.int_between(0, 25))}{rng.int_between(100, 999)}"
        cents = rng.int_between(299, 49999)
        name = f"{adjective} {material} {noun} {model}"
        description = (
            f"{adjective} {noun.lower()} made with {material.lower()}. "
            f"Model {model}, category {categories[cid - 1][0]}."
        )
        product_rows.append([
            str(pid),
            sql_str(name),
            sql_str(f"https://images.shopsphere.test/products/{pid}.jpg"),
            f"{cents // 100}.{cents % 100:02d}",
            str(cents),
            sql_str(description),
            str(cid),
            str(REGULAR_STOCK),
        ])

    hot_sku_id = args.products + 1
    product_rows.append([
        str(hot_sku_id),
        sql_str("Benchmark Hot SKU"),
        sql_str(f"https://images.shopsphere.test/products/{hot_sku_id}.jpg"),
        "19.99",
        "1999",
        sql_str("Constrained-inventory product for checkout contention tests."),
        "1",
        str(args.hot_sku_stock),
    ])

    user_rows = []
    for n in range(1, args.users + 1):
        user_rows.append([
            str(FIRST_USER_ID + n - 1),
            sql_str("Bench"),
            sql_str(f"User{n:04d}"),
            sql_str(f"bench{n:04d}@bench.shopsphere.test"),
            sql_str(BENCH_PASSWORD_HASH),
            sql_str("USER"),
        ])

    cart_rows = []
    for n in range(1, min(args.cart_users, args.users) + 1):
        cart_rows.append([
            str(n),
            sql_str(FIXED_TIMESTAMP),
            str(hot_sku_id),
            str(FIRST_USER_ID + n - 1),
            "1",
        ])

    statements = [
        f"-- ShopSphere benchmark fixture v{FIXTURE_VERSION}. Generated by benchmarks/seed/generate_fixture.py; do not edit.",
        f"-- seed={args.seed} products={args.products} categories={args.categories} users={args.users}",
        "SET FOREIGN_KEY_CHECKS = 0;",
        "TRUNCATE TABLE orderitems;",
        "TRUNCATE TABLE orders;",
        "TRUNCATE TABLE cart;",
        "TRUNCATE TABLE wishlist;",
        "TRUNCATE TABLE processed_stripe_events;",
        "TRUNCATE TABLE products;",
        "TRUNCATE TABLE categories;",
        f"DELETE FROM users WHERE id >= {FIRST_USER_ID} OR email LIKE '%@bench.shopsphere.test';",
        "SET FOREIGN_KEY_CHECKS = 1;",
    ]
    statements += batched_insert("categories", ["id", "category_name", "description", "image_url"], category_rows)
    statements += batched_insert(
        "products", ["id", "name", "image_url", "price", "price_cents", "description", "category_id", "stock"],
        product_rows)
    statements += batched_insert("users", ["id", "first_name", "last_name", "email", "password", "role"], user_rows)
    statements += batched_insert("cart", ["id", "created_date", "product_id", "user_id", "quantity"], cart_rows)
    statements.append("ANALYZE TABLE categories, products, users, cart;")
    sql = "\n".join(statements) + "\n"

    manifest = {
        "fixture_version": FIXTURE_VERSION,
        "seed": args.seed,
        "sql_sha256": hashlib.sha256(sql.encode()).hexdigest(),
        "categories": len(category_rows),
        "catalog_products": args.products,
        "catalog_product_ids": [1, args.products],
        "total_products": len(product_rows),
        "hot_sku": {"id": hot_sku_id, "stock": args.hot_sku_stock},
        "users": {
            "count": len(user_rows),
            "first_id": FIRST_USER_ID,
            "email_pattern": "bench{n:04d}@bench.shopsphere.test",
            "password": BENCH_PASSWORD,
        },
        "carts_with_hot_sku": len(cart_rows),
        # Catalog products are assigned round-robin, so category c holds ids c, c+C, c+2C, ...
        "category_assignment": "round_robin",
        "regular_stock": REGULAR_STOCK,
    }
    return sql, manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--seed", type=int, default=20261109)
    parser.add_argument("--products", type=int, default=10_000)
    parser.add_argument("--categories", type=int, default=12)
    parser.add_argument("--users", type=int, default=200)
    parser.add_argument("--cart-users", type=int, default=100)
    parser.add_argument("--hot-sku-stock", type=int, default=20)
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()

    sql, manifest = build(args)
    args.out_dir.mkdir(parents=True, exist_ok=True)
    (args.out_dir / "fixture.sql").write_text(sql)
    (args.out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"fixture v{FIXTURE_VERSION}: {manifest['total_products']} products, "
          f"{manifest['users']['count']} users, sha256 {manifest['sql_sha256'][:12]}")


if __name__ == "__main__":
    main()
