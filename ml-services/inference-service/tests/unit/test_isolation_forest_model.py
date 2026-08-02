"""Unit tests for inference_service.models.isolation_forest.IsolationForestModel.

This pins the single most safety-critical piece of math in the service: the
sign convention of the raw-score -> [0, 1] normalization. sklearn's
`decision_function` is *higher* for inliers (normal transactions) and
*lower*/negative for outliers (fraud-like). `IsolationForestModel.predict`
must invert that so downstream `risk_level` thresholds treat *higher* as
*more* fraud-like. A stub estimator with a hardcoded `decision_function`
return value pins the direction directly, so a future refactor that
accidentally flips the sign (e.g. drops the negation, or normalizes without
inverting) fails loudly here rather than silently corrupting every
risk_level bucketed downstream.
"""

from __future__ import annotations

import numpy as np

from inference_service.models.isolation_forest import IsolationForestModel


class _StubEstimator:
    """Minimal stand-in for sklearn's IsolationForest: only implements the
    one method IsolationForestModel.predict actually calls.
    """

    def __init__(self, raw_score: float) -> None:
        self._raw_score = raw_score

    def decision_function(self, features: np.ndarray) -> np.ndarray:
        return np.full(features.shape[0], self._raw_score)


def test_predict_with_strongly_positive_raw_score_returns_low_risk_score() -> None:
    # A strongly positive decision_function value is sklearn's clearest
    # "this is a normal transaction" signal -- the normalized score must be
    # close to 0, not close to 1.
    model = IsolationForestModel(estimator=_StubEstimator(raw_score=5.0), version="test")  # type: ignore[arg-type]

    score = model.predict(np.zeros(9))

    assert score < 0.1


def test_predict_with_strongly_negative_raw_score_returns_high_risk_score() -> None:
    # A strongly negative decision_function value is sklearn's clearest
    # "this is an anomaly" signal -- the normalized score must be close to
    # 1, not close to 0.
    model = IsolationForestModel(estimator=_StubEstimator(raw_score=-5.0), version="test")  # type: ignore[arg-type]

    score = model.predict(np.zeros(9))

    assert score > 0.9


def test_predict_with_zero_raw_score_returns_midpoint_score() -> None:
    # decision_function == 0 sits exactly on sklearn's inlier/outlier
    # boundary; the sigmoid's own midpoint should land there too.
    model = IsolationForestModel(estimator=_StubEstimator(raw_score=0.0), version="test")  # type: ignore[arg-type]

    score = model.predict(np.zeros(9))

    assert score == 0.5
