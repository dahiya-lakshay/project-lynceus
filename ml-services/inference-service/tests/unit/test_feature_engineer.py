"""Unit tests for inference_service.models.feature_engineer."""

from __future__ import annotations

import math
from datetime import UTC, datetime

import numpy as np
import pytest

from inference_service.models.feature_engineer import FEATURE_COLUMNS, extract_features
from inference_service.schemas.scoring import MerchantCategory
from tests.conftest import make_score_request


def test_extract_features_with_valid_request_returns_correct_shape_and_dtype() -> None:
    request = make_score_request()

    features = extract_features(request)

    assert features.shape == (len(FEATURE_COLUMNS),)
    assert features.dtype == np.float64


def test_extract_features_column_order_matches_feature_columns_constant() -> None:
    # Guards against FEATURE_COLUMNS and the tuple built in extract_features
    # silently drifting apart, since Task 10's training script depends on
    # this exact order being stable.
    assert FEATURE_COLUMNS == (
        "amount",
        "amount_log",
        "hour_sin",
        "hour_cos",
        "day_of_week_sin",
        "day_of_week_cos",
        "is_online",
        "is_foreign",
        "merchant_category",
    )


def test_extract_features_with_zero_amount_returns_zero_amount_and_zero_log() -> None:
    request = make_score_request(amount="0")

    features = extract_features(request)

    amount_index = FEATURE_COLUMNS.index("amount")
    amount_log_index = FEATURE_COLUMNS.index("amount_log")
    assert features[amount_index] == pytest.approx(0.0)
    assert features[amount_log_index] == pytest.approx(math.log1p(0.0))


def test_extract_features_with_midnight_timestamp_returns_zero_hour_angle() -> None:
    midnight = datetime(2026, 8, 3, 0, 0, 0, tzinfo=UTC)  # a Monday
    request = make_score_request(transaction_timestamp=midnight)

    features = extract_features(request)

    hour_sin_index = FEATURE_COLUMNS.index("hour_sin")
    hour_cos_index = FEATURE_COLUMNS.index("hour_cos")
    # sin(0) == 0, cos(0) == 1: hour=0 sits at angle 0 on the 24h cycle.
    assert features[hour_sin_index] == pytest.approx(0.0, abs=1e-9)
    assert features[hour_cos_index] == pytest.approx(1.0, abs=1e-9)


def test_extract_features_with_monday_timestamp_returns_zero_day_angle() -> None:
    monday = datetime(2026, 8, 3, 12, 0, 0, tzinfo=UTC)
    request = make_score_request(transaction_timestamp=monday)

    features = extract_features(request)

    day_sin_index = FEATURE_COLUMNS.index("day_of_week_sin")
    day_cos_index = FEATURE_COLUMNS.index("day_of_week_cos")
    assert features[day_sin_index] == pytest.approx(0.0, abs=1e-9)
    assert features[day_cos_index] == pytest.approx(1.0, abs=1e-9)


def test_extract_features_with_online_and_foreign_true_encodes_as_one() -> None:
    request = make_score_request(is_online=True, is_foreign=True)

    features = extract_features(request)

    is_online_index = FEATURE_COLUMNS.index("is_online")
    is_foreign_index = FEATURE_COLUMNS.index("is_foreign")
    assert features[is_online_index] == pytest.approx(1.0)
    assert features[is_foreign_index] == pytest.approx(1.0)


def test_extract_features_with_online_and_foreign_false_encodes_as_zero() -> None:
    request = make_score_request(is_online=False, is_foreign=False)

    features = extract_features(request)

    is_online_index = FEATURE_COLUMNS.index("is_online")
    is_foreign_index = FEATURE_COLUMNS.index("is_foreign")
    assert features[is_online_index] == pytest.approx(0.0)
    assert features[is_foreign_index] == pytest.approx(0.0)


@pytest.mark.parametrize("category", list(MerchantCategory))
def test_extract_features_with_each_merchant_category_returns_valid_ordinal_index(
    category: MerchantCategory,
) -> None:
    request = make_score_request(merchant_category=category)

    features = extract_features(request)

    merchant_category_index = FEATURE_COLUMNS.index("merchant_category")
    ordinal = features[merchant_category_index]
    assert 0.0 <= ordinal <= len(MerchantCategory) - 1
    assert ordinal == float(int(ordinal))  # encodes to a whole-number index


def test_extract_features_with_different_categories_returns_distinct_ordinals() -> None:
    # Every category must map to its own index -- a collision would make two
    # different merchant categories indistinguishable to the model.
    merchant_category_index = FEATURE_COLUMNS.index("merchant_category")
    ordinals = {
        extract_features(make_score_request(merchant_category=category))[merchant_category_index]
        for category in MerchantCategory
    }
    assert len(ordinals) == len(MerchantCategory)
