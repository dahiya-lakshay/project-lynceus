"""Unit tests for inference_service.services.scoring_service.ScoringService.

Uses a real (but tiny, random-data-fit) IsolationForest via the
`isolation_forest_model` fixture rather than mocking `predict` outright, so
these tests also exercise the ProcessPoolExecutor round-trip
(feature array -> worker process -> float score) that mocking would skip.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from inference_service.core.config import Settings
from inference_service.core.exceptions import ModelNotLoadedError
from inference_service.models.feature_engineer import FEATURE_COLUMNS
from inference_service.models.isolation_forest import IsolationForestModel
from inference_service.schemas.scoring import RiskLevel
from inference_service.services.scoring_service import ScoringService
from tests.conftest import make_score_request


@pytest.fixture
def settings() -> Settings:
    return Settings()


async def test_score_with_no_model_loaded_raises_model_not_loaded_error(
    settings: Settings,
) -> None:
    service = ScoringService(
        model=None, model_path=Path("models/isolation_forest.joblib"), settings=settings
    )

    with pytest.raises(ModelNotLoadedError):
        await service.score(make_score_request())


async def test_score_with_valid_request_returns_score_in_unit_interval(
    isolation_forest_model: IsolationForestModel,
    tiny_isolation_forest_path: Path,
    settings: Settings,
) -> None:
    service = ScoringService(
        model=isolation_forest_model, model_path=tiny_isolation_forest_path, settings=settings
    )
    request = make_score_request()

    response = await service.score(request)

    assert 0.0 <= response.isolation_forest_score <= 1.0
    # Phase 1 has no Autoencoder yet -- ensemble_score passes through the
    # single available model's score rather than fabricating a combination.
    assert response.ensemble_score == response.isolation_forest_score
    assert response.transaction_id == request.transaction_id
    assert response.tenant_id == request.tenant_id
    assert response.model_version == "test-v1"
    assert set(response.feature_vector.keys()) == set(FEATURE_COLUMNS)


async def test_score_batch_preserves_request_order(
    isolation_forest_model: IsolationForestModel,
    tiny_isolation_forest_path: Path,
    settings: Settings,
) -> None:
    service = ScoringService(
        model=isolation_forest_model, model_path=tiny_isolation_forest_path, settings=settings
    )
    requests = [make_score_request(amount=str(amount)) for amount in ("10.00", "20.00", "30.00")]

    responses = await service.score_batch(requests)

    assert [r.transaction_id for r in responses] == [req.transaction_id for req in requests]


@pytest.mark.parametrize(
    ("score", "expected_risk_level"),
    [
        (0.0, RiskLevel.LOW),
        (0.29, RiskLevel.LOW),
        (0.3, RiskLevel.MEDIUM),  # exactly at the low/medium boundary
        (0.49, RiskLevel.MEDIUM),
        (0.5, RiskLevel.HIGH),  # exactly at the medium/high boundary
        (0.69, RiskLevel.HIGH),
        (0.7, RiskLevel.CRITICAL),  # exactly at the high/critical boundary
        (1.0, RiskLevel.CRITICAL),
    ],
)
def test_compute_risk_level_at_threshold_boundaries(
    score: float, expected_risk_level: RiskLevel, settings: Settings
) -> None:
    service = ScoringService(model=None, model_path=Path("unused"), settings=settings)

    assert service._compute_risk_level(score) == expected_risk_level  # noqa: SLF001
