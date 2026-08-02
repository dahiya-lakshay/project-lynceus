"""Shared pytest fixtures.

No trained model exists in this repo yet (that's Task 10's job — synthetic
data generation + training script). Tests that need a model fit a tiny
throwaway `IsolationForest` on random data purely for test purposes and
save it to a tmp_path fixture, never touching the real
`models/isolation_forest.joblib` path.
"""

from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path
from typing import TYPE_CHECKING
from uuid import uuid4

import joblib
import numpy as np
import pytest
import pytest_asyncio
from sklearn.ensemble import IsolationForest

from inference_service.api.deps import get_settings
from inference_service.models.isolation_forest import IsolationForestModel
from inference_service.schemas.scoring import Channel, MerchantCategory, ScoreTransactionRequest

if TYPE_CHECKING:
    from collections.abc import AsyncIterator

    import httpx


@pytest.fixture(autouse=True)
def _clear_settings_cache() -> None:
    """`get_settings` is `@lru_cache`d for production reuse, but that same
    caching would leak one test's monkeypatched env vars into the next.
    """
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


@pytest.fixture
def tiny_isolation_forest_path(tmp_path: Path) -> Path:
    """Fits a real (but tiny, meaningless) IsolationForest on random data and
    joblib-dumps it to a throwaway path, so tests can exercise the actual
    load -> predict path without depending on Task 10's real training run.
    """
    rng = np.random.default_rng(seed=42)
    training_data = rng.normal(size=(200, 9))
    estimator = IsolationForest(n_estimators=16, random_state=42, contamination="auto")
    estimator.fit(training_data)

    model_path = tmp_path / "isolation_forest.joblib"
    joblib.dump(estimator, model_path)
    return model_path


@pytest.fixture
def isolation_forest_model(tiny_isolation_forest_path: Path) -> IsolationForestModel:
    return IsolationForestModel.load(tiny_isolation_forest_path, version="test-v1")


def make_score_request(
    *,
    amount: str = "125.50",
    merchant_category: MerchantCategory = MerchantCategory.GROCERY,
    is_online: bool = False,
    is_foreign: bool = False,
    channel: Channel = Channel.IN_STORE,
    transaction_timestamp: datetime | None = None,
    tenant_id: str = "tenant-a",
) -> ScoreTransactionRequest:
    """Builds a valid ScoreTransactionRequest with sensible defaults, so
    individual tests only need to override the field(s) they care about.
    """
    return ScoreTransactionRequest(
        transaction_id=uuid4(),
        tenant_id=tenant_id,
        customer_id=uuid4(),
        amount=amount,  # type: ignore[arg-type]
        merchant_category=merchant_category,
        is_online=is_online,
        is_foreign=is_foreign,
        channel=channel,
        transaction_timestamp=transaction_timestamp or datetime(2026, 8, 2, 14, 30, tzinfo=UTC),
    )


@pytest_asyncio.fixture
async def client_with_model(
    monkeypatch: pytest.MonkeyPatch, tiny_isolation_forest_path: Path
) -> AsyncIterator[httpx.AsyncClient]:
    """An httpx.AsyncClient wired to a fresh app instance with a real (tiny,
    throwaway) model loaded, lifespan startup/shutdown included.
    """
    import httpx

    from inference_service.main import create_app

    monkeypatch.setenv("LYNCEUS_INFERENCE_MODEL_PATH", str(tiny_isolation_forest_path))
    monkeypatch.setenv("LYNCEUS_INFERENCE_MODEL_VERSION", "test-v1")
    get_settings.cache_clear()

    app = create_app()
    async with (
        app.router.lifespan_context(app),
        httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client,
    ):
        yield client


@pytest_asyncio.fixture
async def client_without_model(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> AsyncIterator[httpx.AsyncClient]:
    """An httpx.AsyncClient wired to a fresh app instance where the model
    path does not exist — the normal pre-Task-10 state.
    """
    import httpx

    from inference_service.main import create_app

    monkeypatch.setenv("LYNCEUS_INFERENCE_MODEL_PATH", str(tmp_path / "no_model_here.joblib"))
    get_settings.cache_clear()

    app = create_app()
    async with (
        app.router.lifespan_context(app),
        httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client,
    ):
        yield client


@pytest.fixture(scope="session", autouse=True)
def _shutdown_process_pool() -> AsyncIterator[None]:
    """The scoring service's ProcessPoolExecutor is a lazily-created module
    singleton (see scoring_service.py) — shut it down once at the end of the
    whole test session so worker processes don't linger past pytest exit.
    """
    yield
    from inference_service.services.scoring_service import shutdown_executor

    shutdown_executor()


__all__ = ["make_score_request"]
