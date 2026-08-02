"""Synthetic transaction data generator for training the Isolation Forest fraud model.

Generates a CSV whose columns are a superset of the `transactions` table
(infrastructure/db/migrations/changelogs/20260802-01-create-transactions-table.yaml):
every real DB column is populated, plus one extra column, `is_fraud`.

IMPORTANT: `is_fraud` is a synthetic-data-only label used for training/evaluation.
It is NOT a column on the real `transactions` table — `seed_database.py` excludes
it from its COPY statement. Never assume any DB-facing code can read `is_fraud`
off a real transaction; there is no ground-truth fraud label in production data,
which is exactly why we train an *unsupervised* model (Isolation Forest) in the
first place.

`amount_bucket` is also never written here: it's a Postgres
`GENERATED ALWAYS AS (...) STORED` column, computed by the database itself from
`amount`. Inserting into it would be rejected outright.

Usage:
    python generate_synthetic_data.py --config configs/data_generation.yaml \\
        --output data/synthetic_transactions.csv
    python generate_synthetic_data.py --seed-db   # also loads the result into Postgres
"""

from __future__ import annotations

import argparse
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd
import yaml

_SCRIPT_DIR = Path(__file__).resolve().parent
_PACKAGE_DIR = _SCRIPT_DIR.parent
_DEFAULT_CONFIG_PATH = _PACKAGE_DIR / "configs" / "data_generation.yaml"
_DEFAULT_OUTPUT_PATH = _PACKAGE_DIR / "data" / "synthetic_transactions.csv"

# Continental US bounding box for home coordinates — plausible without needing
# a real geocoding dependency for a synthetic dataset.
_HOME_LAT_RANGE = (25.0, 49.0)
_HOME_LNG_RANGE = (-124.0, -67.0)
_HOME_COUNTRY = "USA"

# A handful of real city coordinates, each unambiguously >1000km from any
# continental-US home coordinate, used for the geographic_impossibility
# pattern. Verified against haversine_km at generation time regardless.
_FOREIGN_LOCATIONS: tuple[tuple[float, float, str], ...] = (
    (51.5074, -0.1278, "GBR"),  # London
    (48.8566, 2.3522, "FRA"),  # Paris
    (35.6895, 139.6917, "JPN"),  # Tokyo
    (-33.8688, 151.2093, "AUS"),  # Sydney
    (55.7558, 37.6173, "RUS"),  # Moscow
    (6.5244, 3.3792, "NGA"),  # Lagos
    (-23.5505, -46.6333, "BRA"),  # Sao Paulo
    (28.6139, 77.209, "IND"),  # New Delhi
    (39.9042, 116.4074, "CHN"),  # Beijing
    (25.2048, 55.2708, "ARE"),  # Dubai
)

# Rough share of each category's transactions that are card-not-present.
# Drives is_online (and transitively channel), so categories like
# online_shopping skew almost entirely online while gas_station skews almost
# entirely in-person — this is what makes category_anomaly transactions
# (a category the customer has never used) plausible to also be an unusual
# channel for them, without hard-coding that correlation explicitly.
_ONLINE_PROBABILITY: dict[str, float] = {
    "grocery": 0.10,
    "electronics": 0.45,
    "gas_station": 0.03,
    "restaurant": 0.20,
    "online_shopping": 0.95,
    "travel": 0.65,
    "entertainment": 0.55,
    "healthcare": 0.15,
    "utilities": 0.40,
    "clothing": 0.35,
}

# Diurnal weighting for normal-transaction hour-of-day sampling: low overnight,
# rising through the morning, peaking around lunch and early evening. Without
# this, normal transactions would be uniformly spread across all 24 hours,
# which would make the late_night fraud pattern (2-5 AM) statistically
# indistinguishable from normal activity instead of a genuine anomaly.
_HOUR_WEIGHTS = np.array(
    [
        0.2,
        0.15,
        0.1,
        0.1,
        0.1,
        0.2,  # 00:00-05:59
        0.8,
        1.5,
        2.0,
        2.2,
        2.0,
        2.5,  # 06:00-11:59
        3.0,
        2.5,
        2.0,
        2.0,
        2.2,
        2.5,  # 12:00-17:59
        3.2,
        3.0,
        2.5,
        2.0,
        1.5,
        1.0,  # 18:00-23:59
    ]
)
_HOUR_WEIGHTS = _HOUR_WEIGHTS / _HOUR_WEIGHTS.sum()

_MERCHANTS_PER_CATEGORY = 10


@dataclass
class Customer:
    """A synthetic customer's spending profile, driving both normal and fraud generation."""

    customer_id: str
    mean_amount: float
    std_amount: float
    home_lat: float
    home_lng: float
    home_country: str
    preferred_categories: list[str]
    category_weights: np.ndarray
    unused_categories: list[str]
    device_ids: list[str]


def load_config(path: Path) -> dict[str, Any]:
    """Loads and returns the YAML generation config."""
    with path.open(encoding="utf-8") as handle:
        config: dict[str, Any] = yaml.safe_load(handle)
    return config


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Great-circle distance between two lat/lng points, in kilometers.

    Implemented directly rather than pulling in a geo library — a single
    well-known formula doesn't justify a new dependency for this pipeline.
    """
    earth_radius_km = 6371.0
    phi1, phi2 = np.radians(lat1), np.radians(lat2)
    d_phi = np.radians(lat2 - lat1)
    d_lambda = np.radians(lon2 - lon1)
    a = np.sin(d_phi / 2) ** 2 + np.cos(phi1) * np.cos(phi2) * np.sin(d_lambda / 2) ** 2
    return float(2 * earth_radius_km * np.arcsin(np.sqrt(a)))


def random_ip(rng: np.random.Generator) -> str:
    """A fake-but-valid-looking IPv4 address. Not routable/real-world meaningful."""
    octets = rng.integers(1, 255, size=4)
    return ".".join(str(int(o)) for o in octets)


def _pick_channel(is_online: bool, rng: np.random.Generator) -> str:
    """Channel enum values per shared-lib's Channel.java: online, in_store, atm, mobile.

    NOT 'pos' — the transactions table's COMMENT ON COLUMN text ('pos, atm,
    online, mobile') is stale relative to the actual Channel enum both the
    Java shared-lib and the inference service's Pydantic schema declare.
    """
    if is_online:
        return "online" if rng.random() < 0.6 else "mobile"
    return "in_store" if rng.random() < 0.85 else "atm"


def build_customers(
    num_customers: int, categories: list[str], rng: np.random.Generator
) -> list[Customer]:
    """Generates customer spending profiles: mean/std amount, home location,
    a weighted subset of preferred categories, and 1-2 devices.

    `unused_categories` (categories never in a customer's preferred set) is
    precomputed here so the category_anomaly fraud pattern can pick a
    guaranteed-unused category in O(1) instead of rejection sampling.
    """
    customers: list[Customer] = []
    for _ in range(num_customers):
        mean_amount = float(rng.uniform(25.0, 350.0))
        std_amount = max(mean_amount * float(rng.uniform(0.15, 0.45)), 5.0)
        home_lat = float(rng.uniform(*_HOME_LAT_RANGE))
        home_lng = float(rng.uniform(*_HOME_LNG_RANGE))

        num_preferred = int(rng.integers(3, 6))
        preferred = list(rng.choice(categories, size=num_preferred, replace=False))
        # Dirichlet gives weights that sum to 1 with some customers skewing
        # heavily toward one or two favorite categories, others more even —
        # a uniform split across preferred categories would look unnaturally
        # flat compared to real spending behavior.
        weights = rng.dirichlet(np.full(num_preferred, 2.0))
        unused = [c for c in categories if c not in preferred]

        num_devices = int(rng.integers(1, 3))
        device_ids = [f"device-{uuid.uuid4().hex[:12]}" for _ in range(num_devices)]

        customers.append(
            Customer(
                customer_id=str(uuid.uuid4()),
                mean_amount=mean_amount,
                std_amount=std_amount,
                home_lat=home_lat,
                home_lng=home_lng,
                home_country=_HOME_COUNTRY,
                preferred_categories=preferred,
                category_weights=weights,
                unused_categories=unused,
                device_ids=device_ids,
            )
        )
    return customers


def build_merchant_pool(
    categories: list[str], per_category: int = _MERCHANTS_PER_CATEGORY
) -> dict[str, list[tuple[str, str]]]:
    """A fixed pool of (merchant_id, merchant_name) per category, shared across
    all customers — real transaction data has repeat merchants, not a unique
    merchant per transaction.
    """
    pool: dict[str, list[tuple[str, str]]] = {}
    for category in categories:
        label = category.replace("_", " ").title()
        pool[category] = [
            (f"MERCH-{category.upper()}-{i + 1:03d}", f"{label} Store #{i + 1}")
            for i in range(per_category)
        ]
    return pool


def _pick_merchant(
    pool: dict[str, list[tuple[str, str]]], category: str, rng: np.random.Generator
) -> tuple[str, str]:
    options = pool[category]
    return options[int(rng.integers(len(options)))]


def _random_timestamp_diurnal(rng: np.random.Generator, start: datetime, end: datetime) -> datetime:
    """A timestamp uniformly distributed over the date range, but with hour-of-day
    drawn from _HOUR_WEIGHTS so normal traffic looks like real daytime spending.
    """
    total_seconds = (end - start).total_seconds()
    offset_seconds = float(rng.uniform(0, max(total_seconds, 1.0)))
    base = start + timedelta(seconds=offset_seconds)
    hour = int(rng.choice(24, p=_HOUR_WEIGHTS))
    minute = int(rng.integers(0, 60))
    second = int(rng.integers(0, 60))
    return base.replace(hour=hour, minute=minute, second=second, microsecond=0)


def _make_row(
    *,
    customer: Customer,
    merchant_id: str,
    merchant_name: str,
    category: str,
    amount: float,
    timestamp: datetime,
    is_online: bool,
    is_foreign: bool,
    channel: str,
    lat: float,
    lng: float,
    country_code: str,
    device_id: str,
    rng: np.random.Generator,
    is_fraud: bool,
) -> dict[str, Any]:
    """Assembles one CSV row covering every real `transactions` column plus
    the synthetic-only `is_fraud` label. `updated_at` mirrors `created_at`
    since these rows are never mutated after being written — they're seeded
    once as historical data, not created and later updated.
    """
    return {
        "id": str(uuid.uuid4()),
        "tenant_id": "default",
        "customer_id": customer.customer_id,
        "amount": round(max(amount, 0.01), 2),
        "currency": "USD",
        "merchant_name": merchant_name,
        "merchant_category": category,
        "merchant_id": merchant_id,
        "location_lat": round(lat, 7),
        "location_lng": round(lng, 7),
        "country_code": country_code,
        "is_online": bool(is_online),
        "is_foreign": bool(is_foreign),
        "channel": channel,
        "device_id": device_id,
        "ip_address": random_ip(rng),
        "created_at": timestamp.isoformat(),
        "updated_at": timestamp.isoformat(),
        "is_fraud": bool(is_fraud),
    }


def make_normal_transaction(
    customer: Customer,
    merchants: dict[str, list[tuple[str, str]]],
    rng: np.random.Generator,
    start: datetime,
    end: datetime,
) -> dict[str, Any]:
    """amount ~ Normal(customer_mean, customer_std), category drawn from the
    customer's weighted preferences (never an unused category — that's
    reserved for the category_anomaly fraud pattern), location a small offset
    from home.
    """
    category = str(rng.choice(customer.preferred_categories, p=customer.category_weights))
    merchant_id, merchant_name = _pick_merchant(merchants, category, rng)
    amount = float(rng.normal(customer.mean_amount, customer.std_amount))
    timestamp = _random_timestamp_diurnal(rng, start, end)
    is_online = rng.random() < _ONLINE_PROBABILITY.get(category, 0.2)
    channel = _pick_channel(is_online, rng)
    lat = customer.home_lat + float(rng.normal(0, 0.03))
    lng = customer.home_lng + float(rng.normal(0, 0.03))

    # A small legitimate-foreign-travel slice so is_foreign isn't perfectly
    # collinear with fraud — a model that learns "foreign == fraud" from a
    # dataset where that's always true wouldn't generalize.
    is_foreign = category == "travel" and rng.random() < 0.03
    country_code = customer.home_country
    if is_foreign:
        _, _, country_code = _FOREIGN_LOCATIONS[int(rng.integers(len(_FOREIGN_LOCATIONS)))]

    device_id = str(rng.choice(customer.device_ids))
    return _make_row(
        customer=customer,
        merchant_id=merchant_id,
        merchant_name=merchant_name,
        category=category,
        amount=amount,
        timestamp=timestamp,
        is_online=is_online,
        is_foreign=is_foreign,
        channel=channel,
        lat=lat,
        lng=lng,
        country_code=country_code,
        device_id=device_id,
        rng=rng,
        is_fraud=False,
    )


def make_high_amount_fraud(
    customer: Customer,
    merchants: dict[str, list[tuple[str, str]]],
    pattern_cfg: dict[str, Any],
    rng: np.random.Generator,
    start: datetime,
    end: datetime,
) -> dict[str, Any]:
    """Amount 5-10x (config-driven range) the customer's mean — a single
    outsized purchase, same merchant category and home location a customer
    would normally use, so amount alone is the anomaly signal.
    """
    category = str(rng.choice(customer.preferred_categories, p=customer.category_weights))
    merchant_id, merchant_name = _pick_merchant(merchants, category, rng)
    multiplier_lo, multiplier_hi = pattern_cfg["multiplier_range"]
    amount = customer.mean_amount * float(rng.uniform(multiplier_lo, multiplier_hi))
    timestamp = _random_timestamp_diurnal(rng, start, end)
    is_online = rng.random() < 0.6
    channel = _pick_channel(is_online, rng)
    lat = customer.home_lat + float(rng.normal(0, 0.05))
    lng = customer.home_lng + float(rng.normal(0, 0.05))
    device_id = str(rng.choice(customer.device_ids))
    return _make_row(
        customer=customer,
        merchant_id=merchant_id,
        merchant_name=merchant_name,
        category=category,
        amount=amount,
        timestamp=timestamp,
        is_online=is_online,
        is_foreign=False,
        channel=channel,
        lat=lat,
        lng=lng,
        country_code=customer.home_country,
        device_id=device_id,
        rng=rng,
        is_fraud=True,
    )


def make_velocity_burst_cluster(
    customer: Customer,
    merchants: dict[str, list[tuple[str, str]]],
    pattern_cfg: dict[str, Any],
    rng: np.random.Generator,
    start: datetime,
    end: datetime,
    size: int,
) -> list[dict[str, Any]]:
    """`size` (>= config's min_transactions) transactions packed into a single
    window_hours window for one customer — simulates card-testing/velocity
    abuse. Skews online (card-not-present is the common real-world vector for
    rapid-fire small-value probing) and slightly below the customer's normal
    mean (testing transactions are often kept small to avoid detection).
    """
    window_hours = pattern_cfg["window_hours"]
    latest_start = end - timedelta(hours=window_hours + 1)
    burst_start = _random_timestamp_diurnal(
        rng, start, latest_start if latest_start > start else start
    )
    rows = []
    for _ in range(size):
        offset_minutes = float(rng.uniform(0, window_hours * 60))
        # Whole-second precision, matching every other timestamp this script
        # writes (see _random_timestamp_diurnal) — timedelta(minutes=<float>)
        # otherwise introduces microseconds that make the CSV's created_at
        # column mixed-precision and harder for downstream ISO8601 parsers.
        timestamp = (burst_start + timedelta(minutes=offset_minutes)).replace(microsecond=0)
        category = str(rng.choice(customer.preferred_categories, p=customer.category_weights))
        merchant_id, merchant_name = _pick_merchant(merchants, category, rng)
        amount = float(rng.normal(customer.mean_amount * 0.6, customer.std_amount * 0.5))
        is_online = True
        channel = _pick_channel(is_online, rng)
        lat = customer.home_lat + float(rng.normal(0, 0.02))
        lng = customer.home_lng + float(rng.normal(0, 0.02))
        device_id = str(rng.choice(customer.device_ids))
        rows.append(
            _make_row(
                customer=customer,
                merchant_id=merchant_id,
                merchant_name=merchant_name,
                category=category,
                amount=amount,
                timestamp=timestamp,
                is_online=is_online,
                is_foreign=False,
                channel=channel,
                lat=lat,
                lng=lng,
                country_code=customer.home_country,
                device_id=device_id,
                rng=rng,
                is_fraud=True,
            )
        )
    return rows


def make_geo_impossibility_pair(
    customer: Customer,
    merchants: dict[str, list[tuple[str, str]]],
    pattern_cfg: dict[str, Any],
    rng: np.random.Generator,
    start: datetime,
    end: datetime,
) -> list[dict[str, Any]]:
    """A normal transaction near home, followed within max_time_hours by a
    transaction >= min_distance_km away — physically impossible to travel
    between in the elapsed time. Only the second (impossible) leg is labeled
    fraud; the first is a genuine normal transaction, included in the output
    so the "previous location" it establishes is a real row in the dataset.
    """
    max_time_hours = pattern_cfg["max_time_hours"]
    min_distance_km = pattern_cfg["min_distance_km"]

    latest_start = end - timedelta(hours=max_time_hours + 1)
    prev_timestamp = _random_timestamp_diurnal(
        rng, start, latest_start if latest_start > start else start
    )
    prev_category = str(rng.choice(customer.preferred_categories, p=customer.category_weights))
    prev_merchant_id, prev_merchant_name = _pick_merchant(merchants, prev_category, rng)
    prev_amount = float(rng.normal(customer.mean_amount, customer.std_amount))
    prev_lat = customer.home_lat + float(rng.normal(0, 0.03))
    prev_lng = customer.home_lng + float(rng.normal(0, 0.03))
    prev_row = _make_row(
        customer=customer,
        merchant_id=prev_merchant_id,
        merchant_name=prev_merchant_name,
        category=prev_category,
        amount=prev_amount,
        timestamp=prev_timestamp,
        is_online=False,
        is_foreign=False,
        channel=_pick_channel(False, rng),
        lat=prev_lat,
        lng=prev_lng,
        country_code=customer.home_country,
        device_id=str(rng.choice(customer.device_ids)),
        rng=rng,
        is_fraud=False,
    )

    # Pick a far-enough location; the fixed pool is always >1000km from any
    # continental-US home point, but we verify via haversine_km anyway rather
    # than assuming that holds — defensive against future edits to either
    # coordinate set.
    far_lat, far_lng, far_country = _FOREIGN_LOCATIONS[int(rng.integers(len(_FOREIGN_LOCATIONS)))]
    for _ in range(len(_FOREIGN_LOCATIONS)):
        if haversine_km(prev_lat, prev_lng, far_lat, far_lng) >= min_distance_km:
            break
        far_lat, far_lng, far_country = _FOREIGN_LOCATIONS[
            int(rng.integers(len(_FOREIGN_LOCATIONS)))
        ]

    delta_minutes = float(rng.uniform(5, max_time_hours * 60))
    # .replace(microsecond=0): see make_velocity_burst_cluster's identical note.
    fraud_timestamp = (prev_timestamp + timedelta(minutes=delta_minutes)).replace(microsecond=0)
    if fraud_timestamp > end:
        fraud_timestamp = end - timedelta(minutes=1)

    category = str(rng.choice(customer.preferred_categories, p=customer.category_weights))
    merchant_id, merchant_name = _pick_merchant(merchants, category, rng)
    amount = float(rng.normal(customer.mean_amount, customer.std_amount))
    fraud_row = _make_row(
        customer=customer,
        merchant_id=merchant_id,
        merchant_name=merchant_name,
        category=category,
        amount=amount,
        timestamp=fraud_timestamp,
        is_online=False,
        is_foreign=True,
        channel=_pick_channel(False, rng),
        lat=far_lat,
        lng=far_lng,
        country_code=far_country,
        device_id=str(rng.choice(customer.device_ids)),
        rng=rng,
        is_fraud=True,
    )
    return [prev_row, fraud_row]


def make_category_anomaly_fraud(
    customer: Customer,
    merchants: dict[str, list[tuple[str, str]]],
    rng: np.random.Generator,
    start: datetime,
    end: datetime,
) -> dict[str, Any]:
    """A transaction in a category this customer has never used (see
    `Customer.unused_categories`, precomputed in build_customers). Every
    customer prefers 3-5 of the 10 categories, so at least 5 unused
    categories always exist — no rejection sampling needed.
    """
    category = str(rng.choice(customer.unused_categories))
    merchant_id, merchant_name = _pick_merchant(merchants, category, rng)
    amount = float(rng.normal(customer.mean_amount, customer.std_amount))
    timestamp = _random_timestamp_diurnal(rng, start, end)
    is_online = rng.random() < _ONLINE_PROBABILITY.get(category, 0.2)
    channel = _pick_channel(is_online, rng)
    lat = customer.home_lat + float(rng.normal(0, 0.03))
    lng = customer.home_lng + float(rng.normal(0, 0.03))
    device_id = str(rng.choice(customer.device_ids))
    return _make_row(
        customer=customer,
        merchant_id=merchant_id,
        merchant_name=merchant_name,
        category=category,
        amount=amount,
        timestamp=timestamp,
        is_online=is_online,
        is_foreign=False,
        channel=channel,
        lat=lat,
        lng=lng,
        country_code=customer.home_country,
        device_id=device_id,
        rng=rng,
        is_fraud=True,
    )


def make_late_night_cluster(
    customer: Customer,
    merchants: dict[str, list[tuple[str, str]]],
    pattern_cfg: dict[str, Any],
    rng: np.random.Generator,
    start: datetime,
    end: datetime,
    size: int,
) -> list[dict[str, Any]]:
    """A cluster of `size` transactions, all with hour-of-day inside the
    configured hour_range (default 2-5 AM) — overrides the normal diurnal
    weighting deliberately, since that weighting is exactly what makes this
    hour window rare (and therefore anomalous) for genuine activity.
    """
    hour_lo, hour_hi = pattern_cfg["hour_range"]
    total_seconds = (end - start).total_seconds()
    base_date = start + timedelta(seconds=float(rng.uniform(0, max(total_seconds, 1.0))))
    rows = []
    for _ in range(size):
        hour = int(rng.integers(hour_lo, hour_hi + 1))
        minute = int(rng.integers(0, 60))
        second = int(rng.integers(0, 60))
        timestamp = base_date.replace(hour=hour, minute=minute, second=second, microsecond=0)
        category = str(rng.choice(customer.preferred_categories, p=customer.category_weights))
        merchant_id, merchant_name = _pick_merchant(merchants, category, rng)
        amount = float(rng.normal(customer.mean_amount, customer.std_amount))
        is_online = rng.random() < 0.7
        channel = _pick_channel(is_online, rng)
        lat = customer.home_lat + float(rng.normal(0, 0.03))
        lng = customer.home_lng + float(rng.normal(0, 0.03))
        device_id = str(rng.choice(customer.device_ids))
        rows.append(
            _make_row(
                customer=customer,
                merchant_id=merchant_id,
                merchant_name=merchant_name,
                category=category,
                amount=amount,
                timestamp=timestamp,
                is_online=is_online,
                is_foreign=False,
                channel=channel,
                lat=lat,
                lng=lng,
                country_code=customer.home_country,
                device_id=device_id,
                rng=rng,
                is_fraud=True,
            )
        )
    return rows


def _allocate_fraud_targets(fraud_total: int, fraud_patterns_cfg: dict[str, Any]) -> dict[str, int]:
    """Splits the total fraud row budget across pattern types per their
    `weight`. The last pattern absorbs the rounding remainder so the targets
    always sum to exactly `fraud_total`, rather than drifting a row or two
    off due to independent rounding of each pattern's share.
    """
    names = list(fraud_patterns_cfg.keys())
    targets: dict[str, int] = {}
    allocated = 0
    for index, name in enumerate(names):
        if index == len(names) - 1:
            targets[name] = max(fraud_total - allocated, 0)
        else:
            count = round(fraud_total * fraud_patterns_cfg[name]["weight"])
            targets[name] = count
            allocated += count
    return targets


def generate_dataset(config: dict[str, Any], rng: np.random.Generator) -> pd.DataFrame:
    """Builds the full synthetic transaction dataset per `config`.

    Row-count bookkeeping: fraud patterns are generated first against their
    per-pattern target counts (some, like geographic_impossibility, also emit
    a companion normal row), then the remaining budget up to
    `num_transactions` is filled with pure normal transactions. Final row
    order is shuffled — arrival order across customers, not grouped by
    pattern — via an index permutation (not `Generator.shuffle` on a list of
    dicts, whose in-place semantics on non-ndarray sequences aren't as
    predictable).
    """
    gen_cfg = config["generation"]
    num_transactions = gen_cfg["num_transactions"]
    fraud_rate = gen_cfg["fraud_rate"]
    start = datetime.fromisoformat(gen_cfg["date_range"]["start"])
    end = datetime.fromisoformat(gen_cfg["date_range"]["end"])
    categories: list[str] = config["merchants"]["categories"]

    customers = build_customers(gen_cfg["num_customers"], categories, rng)
    merchants = build_merchant_pool(categories)

    fraud_patterns_cfg = config["fraud_patterns"]
    fraud_total = round(num_transactions * fraud_rate)
    targets = _allocate_fraud_targets(fraud_total, fraud_patterns_cfg)

    rows: list[dict[str, Any]] = []

    for _ in range(targets["high_amount"]):
        customer = customers[int(rng.integers(len(customers)))]
        rows.append(
            make_high_amount_fraud(
                customer, merchants, fraud_patterns_cfg["high_amount"], rng, start, end
            )
        )

    remaining = targets["velocity_burst"]
    min_burst = fraud_patterns_cfg["velocity_burst"]["min_transactions"]
    while remaining > 0:
        size = (
            min(min_burst + int(rng.integers(0, 3)), remaining)
            if remaining >= min_burst
            else remaining
        )
        size = max(size, 1)
        customer = customers[int(rng.integers(len(customers)))]
        rows.extend(
            make_velocity_burst_cluster(
                customer, merchants, fraud_patterns_cfg["velocity_burst"], rng, start, end, size
            )
        )
        remaining -= size

    for _ in range(targets["geographic_impossibility"]):
        customer = customers[int(rng.integers(len(customers)))]
        rows.extend(
            make_geo_impossibility_pair(
                customer, merchants, fraud_patterns_cfg["geographic_impossibility"], rng, start, end
            )
        )

    for _ in range(targets["category_anomaly"]):
        customer = customers[int(rng.integers(len(customers)))]
        rows.append(make_category_anomaly_fraud(customer, merchants, rng, start, end))

    remaining = targets["late_night"]
    while remaining > 0:
        size = min(int(rng.integers(2, 5)), remaining)
        size = max(size, 1)
        customer = customers[int(rng.integers(len(customers)))]
        rows.extend(
            make_late_night_cluster(
                customer, merchants, fraud_patterns_cfg["late_night"], rng, start, end, size
            )
        )
        remaining -= size

    normal_needed = max(num_transactions - len(rows), 0)
    for _ in range(normal_needed):
        customer = customers[int(rng.integers(len(customers)))]
        rows.append(make_normal_transaction(customer, merchants, rng, start, end))

    shuffled_indices = rng.permutation(len(rows))
    rows = [rows[i] for i in shuffled_indices]

    return pd.DataFrame(rows)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--config", type=Path, default=_DEFAULT_CONFIG_PATH, help="Path to data_generation.yaml"
    )
    parser.add_argument("--output", type=Path, default=_DEFAULT_OUTPUT_PATH, help="Output CSV path")
    parser.add_argument(
        "--seed-db",
        action="store_true",
        help="After generating, also bulk-load the CSV into Postgres via seed_database.py",
    )
    parser.add_argument("--seed", type=int, default=None, help="Override the config's RNG seed")
    parser.add_argument(
        "--num-customers", type=int, default=None, help="Override generation.num_customers"
    )
    parser.add_argument(
        "--num-transactions", type=int, default=None, help="Override generation.num_transactions"
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    config = load_config(args.config)

    if args.num_customers is not None:
        config["generation"]["num_customers"] = args.num_customers
    if args.num_transactions is not None:
        config["generation"]["num_transactions"] = args.num_transactions
    if args.seed is not None:
        config["generation"]["seed"] = args.seed

    rng = np.random.default_rng(config["generation"]["seed"])
    dataframe = generate_dataset(config, rng)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    dataframe.to_csv(args.output, index=False)

    fraud_count = int(dataframe["is_fraud"].sum())
    total = len(dataframe)
    print(
        f"Generated {total} transactions ({fraud_count} fraud, {fraud_count / total:.2%}) "
        f"across {config['generation']['num_customers']} customers -> {args.output}"
    )

    if args.seed_db:
        # Deferred import: seed_database.py needs psycopg, which generation
        # itself doesn't — importing only when actually seeding keeps a
        # plain `--config ... --output ...` run from requiring a live DB
        # driver import to succeed.
        from seed_database import seed_from_csv

        inserted = seed_from_csv(args.output)
        print(f"Seeded {inserted} rows into the transactions table.")


if __name__ == "__main__":
    main()
