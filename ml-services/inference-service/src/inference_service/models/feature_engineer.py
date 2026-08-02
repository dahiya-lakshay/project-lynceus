"""Feature extraction for the Isolation Forest fraud model.

`FEATURE_COLUMNS` fixes the exact feature order the model is trained and
scored on. Task 10's training script must import `FEATURE_COLUMNS` and
`extract_features` from here rather than re-deriving the same logic, so
training and serving can never silently drift apart — a classic source of
train/serve skew bugs in ML systems.
"""

from __future__ import annotations

import math

import numpy as np

from inference_service.schemas.scoring import MerchantCategory, ScoreTransactionRequest

# Ordinal encoding table. Order matches the `merchant_category` enum in
# api-specs/shared/schemas/transaction.yaml exactly. Isolation Forest is a
# tree-based, scale-invariant model, so an arbitrary-but-fixed ordinal index
# is sufficient here — we don't need one-hot encoding's independence
# guarantees the way a linear/distance-based model would.
_MERCHANT_CATEGORY_ORDER: tuple[MerchantCategory, ...] = (
    MerchantCategory.GROCERY,
    MerchantCategory.ELECTRONICS,
    MerchantCategory.GAS_STATION,
    MerchantCategory.RESTAURANT,
    MerchantCategory.ONLINE_SHOPPING,
    MerchantCategory.TRAVEL,
    MerchantCategory.ENTERTAINMENT,
    MerchantCategory.HEALTHCARE,
    MerchantCategory.UTILITIES,
    MerchantCategory.CLOTHING,
)
_MERCHANT_CATEGORY_INDEX: dict[MerchantCategory, int] = {
    category: index for index, category in enumerate(_MERCHANT_CATEGORY_ORDER)
}

# Fixed feature order — see module docstring. Cyclical (sin/cos) encodings for
# hour-of-day and day-of-week avoid the false discontinuity a raw integer
# would introduce (e.g. hour 23 and hour 0 are one hour apart, not 23).
FEATURE_COLUMNS: tuple[str, ...] = (
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

_HOURS_PER_DAY = 24
_DAYS_PER_WEEK = 7


def extract_features(request: ScoreTransactionRequest) -> np.ndarray:
    """Convert a scoring request into the model's fixed-order feature vector.

    Returns a 1-D float64 array with len(FEATURE_COLUMNS) elements, in the
    exact order FEATURE_COLUMNS declares.
    """
    amount = float(request.amount)
    # amount is left unscaled (beyond the Decimal -> float64 cast) rather than
    # standardized: Isolation Forest partitions feature ranges via random
    # splits and doesn't assume any particular distribution, so distribution
    # normalization (e.g. z-score) would add complexity without changing
    # model behavior. amount_log captures the multiplicative, long-tailed
    # nature of transaction amounts that the raw value alone doesn't.
    amount_log = math.log1p(amount)

    hour = request.transaction_timestamp.hour
    hour_angle = 2 * math.pi * hour / _HOURS_PER_DAY
    hour_sin = math.sin(hour_angle)
    hour_cos = math.cos(hour_angle)

    day_of_week = request.transaction_timestamp.weekday()  # Monday=0 .. Sunday=6
    day_angle = 2 * math.pi * day_of_week / _DAYS_PER_WEEK
    day_sin = math.sin(day_angle)
    day_cos = math.cos(day_angle)

    merchant_category_index = _MERCHANT_CATEGORY_INDEX[request.merchant_category]

    features = (
        amount,
        amount_log,
        hour_sin,
        hour_cos,
        day_sin,
        day_cos,
        float(request.is_online),
        float(request.is_foreign),
        float(merchant_category_index),
    )
    return np.array(features, dtype=np.float64)


__all__ = ["FEATURE_COLUMNS", "extract_features"]
