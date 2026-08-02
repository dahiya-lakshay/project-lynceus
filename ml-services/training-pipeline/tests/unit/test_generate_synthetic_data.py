"""Unit tests for generate_synthetic_data.py's fraud-pattern generators.

These assert the *raw* generated values (amount, timestamps, lat/lng) really
exhibit the anomalous property each pattern claims to produce — e.g. that
make_geo_impossibility_pair's two rows really are >= min_distance_km apart
within max_time_hours, not just that the function runs without error. This
is deliberately independent of whether the current Phase 1 feature set can
detect the pattern once extracted (see train_isolation_forest.py's module
docstring for that separate, measured concern) — a bug in the raw synthetic
data would be far more expensive to catch only after a full training run.
"""

from __future__ import annotations

from datetime import datetime, timedelta

import numpy as np
import pytest

from scripts.generate_synthetic_data import (
    PATTERN_CATEGORY_ANOMALY,
    PATTERN_GEOGRAPHIC_IMPOSSIBILITY,
    PATTERN_HIGH_AMOUNT,
    PATTERN_LATE_NIGHT,
    PATTERN_NORMAL,
    PATTERN_VELOCITY_BURST,
    Customer,
    build_customers,
    build_merchant_pool,
    haversine_km,
    make_category_anomaly_fraud,
    make_geo_impossibility_pair,
    make_high_amount_fraud,
    make_late_night_cluster,
    make_velocity_burst_cluster,
)

# Mirrors configs/data_generation.yaml's merchants.categories exactly (see that
# file's own comment on why this list must match MerchantCategory) — hardcoded
# here rather than loaded from YAML so these unit tests don't depend on the
# config file's on-disk state.
_CATEGORIES: list[str] = [
    "grocery",
    "electronics",
    "gas_station",
    "restaurant",
    "online_shopping",
    "travel",
    "entertainment",
    "healthcare",
    "utilities",
    "clothing",
]
_SEED = 20260802
_START = datetime.fromisoformat("2026-01-01T00:00:00")
_END = datetime.fromisoformat("2026-08-01T00:00:00")


def _build_fixture(num_customers: int = 10) -> tuple[list[Customer], dict, np.random.Generator]:
    """A freshly-seeded rng plus customers/merchants for one test — not a
    shared pytest fixture, since each test consuming further rng draws would
    otherwise depend on how much state earlier tests left it in.
    """
    rng = np.random.default_rng(_SEED)
    customers = build_customers(num_customers, _CATEGORIES, rng)
    merchants = build_merchant_pool(_CATEGORIES)
    return customers, merchants, rng


class TestHaversineKm:
    def test_same_point_returns_zero(self) -> None:
        assert haversine_km(40.7128, -74.0060, 40.7128, -74.0060) == pytest.approx(0.0, abs=1e-6)

    def test_known_city_pair_matches_reference_distance(self) -> None:
        # New York to London: well-documented great-circle distance ~5570km.
        distance = haversine_km(40.7128, -74.0060, 51.5074, -0.1278)
        assert distance == pytest.approx(5570, rel=0.02)


class TestBuildCustomers:
    def test_preferred_and_unused_categories_partition_the_full_set(self) -> None:
        customers, _, _ = _build_fixture()
        for customer in customers:
            preferred = set(customer.preferred_categories)
            unused = set(customer.unused_categories)
            assert preferred & unused == set(), "a category can't be both preferred and unused"
            assert preferred | unused == set(_CATEGORIES), "every category must be one or the other"
            assert 3 <= len(preferred) <= 5
            # category_anomaly's no-rejection-sampling guarantee (see its
            # docstring) depends on this never being empty.
            assert len(unused) > 0

    def test_category_weights_sum_to_one_and_align_with_preferred_categories(self) -> None:
        customers, _, _ = _build_fixture()
        for customer in customers:
            assert customer.category_weights.sum() == pytest.approx(1.0)
            assert len(customer.category_weights) == len(customer.preferred_categories)


class TestMakeHighAmountFraud:
    def test_amount_is_within_the_configured_multiplier_range(self) -> None:
        customers, merchants, rng = _build_fixture()
        pattern_cfg = {"multiplier_range": [5, 10]}
        customer = customers[0]

        for _ in range(50):
            row = make_high_amount_fraud(customer, merchants, pattern_cfg, rng, _START, _END)
            assert customer.mean_amount * 5 <= row["amount"] <= customer.mean_amount * 10 + 0.01
            assert row["is_fraud"] is True
            assert row["fraud_pattern"] == PATTERN_HIGH_AMOUNT


class TestMakeVelocityBurstCluster:
    def test_all_rows_land_within_the_configured_window(self) -> None:
        customers, merchants, rng = _build_fixture()
        pattern_cfg = {"window_hours": 1}
        customer = customers[1]
        rows = make_velocity_burst_cluster(
            customer, merchants, pattern_cfg, rng, _START, _END, size=6
        )

        assert len(rows) == 6
        timestamps = sorted(datetime.fromisoformat(row["created_at"]) for row in rows)
        assert timestamps[-1] - timestamps[0] <= timedelta(hours=1)
        assert all(row["is_fraud"] is True for row in rows)
        assert all(row["fraud_pattern"] == PATTERN_VELOCITY_BURST for row in rows)
        assert all(row["customer_id"] == customer.customer_id for row in rows)


class TestMakeGeoImpossibilityPair:
    def test_distance_exceeds_threshold_within_time_budget(self) -> None:
        customers, merchants, rng = _build_fixture()
        pattern_cfg = {"max_time_hours": 1, "min_distance_km": 1000}
        customer = customers[2]

        for _ in range(20):
            prev_row, fraud_row = make_geo_impossibility_pair(
                customer, merchants, pattern_cfg, rng, _START, _END
            )

            distance = haversine_km(
                prev_row["location_lat"],
                prev_row["location_lng"],
                fraud_row["location_lat"],
                fraud_row["location_lng"],
            )
            elapsed = datetime.fromisoformat(fraud_row["created_at"]) - datetime.fromisoformat(
                prev_row["created_at"]
            )

            assert distance >= 1000
            assert timedelta(0) < elapsed <= timedelta(hours=1)
            assert prev_row["is_fraud"] is False
            assert prev_row["fraud_pattern"] == PATTERN_NORMAL
            assert fraud_row["is_fraud"] is True
            assert fraud_row["fraud_pattern"] == PATTERN_GEOGRAPHIC_IMPOSSIBILITY
            assert fraud_row["is_foreign"] is True


class TestMakeCategoryAnomalyFraud:
    def test_category_is_never_one_of_the_customers_preferred_categories(self) -> None:
        customers, merchants, rng = _build_fixture()
        customer = customers[3]

        for _ in range(30):
            row = make_category_anomaly_fraud(customer, merchants, rng, _START, _END)
            assert row["merchant_category"] not in customer.preferred_categories
            assert row["merchant_category"] in customer.unused_categories
            assert row["is_fraud"] is True
            assert row["fraud_pattern"] == PATTERN_CATEGORY_ANOMALY


class TestMakeLateNightCluster:
    def test_every_row_falls_inside_the_configured_hour_range(self) -> None:
        customers, merchants, rng = _build_fixture()
        pattern_cfg = {"hour_range": [2, 5]}
        customer = customers[4]
        rows = make_late_night_cluster(customer, merchants, pattern_cfg, rng, _START, _END, size=4)

        assert len(rows) == 4
        for row in rows:
            hour = datetime.fromisoformat(row["created_at"]).hour
            assert 2 <= hour <= 5
            assert row["is_fraud"] is True
            assert row["fraud_pattern"] == PATTERN_LATE_NIGHT
