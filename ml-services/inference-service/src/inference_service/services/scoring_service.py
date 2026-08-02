"""Fraud scoring orchestration: feature extraction -> model inference -> risk tier.

Model inference runs in a `ProcessPoolExecutor` rather than the asyncio event
loop's thread, per AGENTS.md: "Use ProcessPoolExecutor for CPU-bound ML
inference (Python GIL)". scikit-learn's `decision_function` holds the GIL for
the duration of the call; running it on the event loop thread would stall
every other in-flight request on this instance for the call's duration.
"""

from __future__ import annotations

import asyncio
from concurrent.futures import ProcessPoolExecutor
from datetime import UTC, datetime
from pathlib import Path
from typing import TYPE_CHECKING

from inference_service.core.exceptions import ModelNotLoadedError
from inference_service.models.feature_engineer import FEATURE_COLUMNS, extract_features
from inference_service.models.isolation_forest import IsolationForestModel
from inference_service.schemas.scoring import (
    RiskLevel,
    ScoreTransactionRequest,
    ScoreTransactionResponse,
)

if TYPE_CHECKING:
    import numpy as np

    from inference_service.core.config import Settings

# Lazily instantiated on first use (rather than at import time) so importing
# this module — e.g. during pytest collection — never spawns worker
# processes as a side effect. Still created exactly once and reused for the
# lifetime of the process, satisfying "module-level, created once, not
# per-request": the singleton lives at module scope, only its construction
# is deferred.
_executor: ProcessPoolExecutor | None = None

# Per-worker-process model cache. Each ProcessPoolExecutor worker is a
# separate interpreter with its own copy of module globals, so this is safe
# without locking — it's never shared across processes. Re-checked against
# model_version on every call so a hot-reloaded model (future work) doesn't
# leave stale workers serving an old model indefinitely.
_worker_model: IsolationForestModel | None = None


def _get_executor(max_workers: int) -> ProcessPoolExecutor:
    global _executor
    if _executor is None:
        _executor = ProcessPoolExecutor(max_workers=max_workers)
    return _executor


def shutdown_executor() -> None:
    """Called from the app lifespan's shutdown phase so worker processes are
    terminated cleanly on server shutdown instead of leaking until the
    parent process exits (or, worse, becoming orphaned zombie processes).
    """
    global _executor
    if _executor is not None:
        _executor.shutdown(wait=True, cancel_futures=True)
        _executor = None


def _predict_worker(model_path: str, model_version: str, features: np.ndarray) -> float:
    """Runs inside a worker process. Must stay a top-level function (not a
    closure/lambda) so it remains picklable for `ProcessPoolExecutor` under
    the `spawn` start method, which macOS and Python 3.14+ use by default.
    """
    global _worker_model
    if _worker_model is None or _worker_model.version != model_version:
        _worker_model = IsolationForestModel.load(Path(model_path), model_version)
    return _worker_model.predict(features)


class ScoringService:
    """Scores transactions against the loaded Isolation Forest model."""

    def __init__(
        self,
        model: IsolationForestModel | None,
        model_path: Path,
        settings: Settings,
    ) -> None:
        self._model = model
        self._model_path = model_path
        self._settings = settings

    async def score(self, request: ScoreTransactionRequest) -> ScoreTransactionResponse:
        """Score a single transaction. Raises ModelNotLoadedError (-> 503) if
        no model has been loaded yet — expected until Task 10 trains one.
        """
        if self._model is None:
            raise ModelNotLoadedError()

        features = extract_features(request)
        loop = asyncio.get_running_loop()
        isolation_forest_score = await loop.run_in_executor(
            _get_executor(self._settings.process_pool_workers),
            _predict_worker,
            str(self._model_path),
            self._model.version,
            features,
        )

        # Phase 1 ships only the Isolation Forest model. The response schema
        # already has an `ensemble_score` field (the Autoencoder arrives in a
        # later task and will make this a real weighted ensemble) — until
        # then it passes through the single available model's score rather
        # than fabricating a combination from a model that doesn't exist.
        ensemble_score = isolation_forest_score

        return ScoreTransactionResponse(
            transaction_id=request.transaction_id,
            tenant_id=request.tenant_id,
            isolation_forest_score=isolation_forest_score,
            ensemble_score=ensemble_score,
            risk_level=self._compute_risk_level(ensemble_score),
            model_version=self._model.version,
            feature_vector=dict(zip(FEATURE_COLUMNS, features.tolist(), strict=True)),
            scored_at=datetime.now(UTC),
        )

    async def score_batch(
        self, requests: list[ScoreTransactionRequest]
    ) -> list[ScoreTransactionResponse]:
        """Score a batch concurrently, preserving request order in the response
        (required by the /batch endpoint's spec: "in request order").
        `asyncio.gather` preserves input order regardless of completion order.
        """
        return list(await asyncio.gather(*(self.score(req) for req in requests)))

    def _compute_risk_level(self, score: float) -> RiskLevel:
        # Fixed business thresholds from the Task 7 spec, sourced from
        # settings so they're tunable per environment without a code change.
        if score < self._settings.risk_threshold_low:
            return RiskLevel.LOW
        if score < self._settings.risk_threshold_medium:
            return RiskLevel.MEDIUM
        if score < self._settings.risk_threshold_high:
            return RiskLevel.HIGH
        return RiskLevel.CRITICAL


__all__ = ["ScoringService", "shutdown_executor"]
