"""Isolation Forest model wrapper: load a trained estimator and score features.

Score normalization: scikit-learn's `IsolationForest.decision_function`
returns *higher* values for inliers (normal transactions) and *lower*
(often negative) values for outliers (anomalies/fraud), on a scale that
isn't bounded and isn't comparable across training runs. Downstream
`risk_level` thresholds (scoring_service.py) need a stable [0, 1] scale
where *higher* means *more* fraud-like, so we invert and squash the raw
score through a sigmoid: `1 / (1 + e^(raw_score * STEEPNESS))`.

WHY a fixed-steepness sigmoid instead of min-max scaling against the
model's own training-time score range (the more precise alternative the
Task 7 spec calls out): Task 10 hasn't run yet, so there is no persisted
min/max to scale against, and this wrapper has no access to the training
set at serving time. A sigmoid needs no stored statistics, is monotonic
(preserves rank order, which is all `risk_level` bucketing depends on),
and saturates gracefully at the extremes instead of clipping. If Task 10
persists decision_function min/max alongside the model artifact, `load()`
can pick them up and this can switch to true min-max scaling without
changing the public `predict()` contract.
"""

from __future__ import annotations

import math
from pathlib import Path
from typing import TYPE_CHECKING

import joblib
import numpy as np

if TYPE_CHECKING:
    from sklearn.ensemble import IsolationForest

# Controls how sharply the sigmoid separates inliers from outliers.
# decision_function output is typically within roughly [-0.5, 0.5] for
# scikit-learn's default contamination settings; a steepness of 10
# spreads that range across most of [0, 1] without either flattening
# everything toward 0.5 (too low) or saturating almost everything to 0/1
# (too high, which would make risk_level thresholds meaningless).
_SIGMOID_STEEPNESS = 10.0


class IsolationForestModel:
    """Thin wrapper around a fitted `sklearn.ensemble.IsolationForest`.

    Keeps sklearn specifics (decision_function, score normalization) out of
    the service layer so `ScoringService` only deals with a plain
    `predict(features) -> float` contract.
    """

    def __init__(self, estimator: IsolationForest, version: str) -> None:
        self._estimator = estimator
        self.version = version

    @classmethod
    def load(cls, path: Path, version: str) -> IsolationForestModel:
        """Load a joblib-serialized IsolationForest from disk.

        Raises FileNotFoundError / joblib's own exceptions on failure — the
        caller (main.py lifespan) decides whether that's fatal to startup.
        """
        estimator: IsolationForest = joblib.load(path)
        return cls(estimator=estimator, version=version)

    def predict(self, features: np.ndarray) -> float:
        """Score a single feature vector, returning an anomaly score in [0, 1].

        `features` must be a 1-D array in FEATURE_COLUMNS order; reshaped to
        the (1, n_features) sklearn expects for a single sample.
        """
        raw_score = float(self._estimator.decision_function(features.reshape(1, -1))[0])
        return 1.0 / (1.0 + math.exp(raw_score * _SIGMOID_STEEPNESS))


__all__ = ["IsolationForestModel"]
