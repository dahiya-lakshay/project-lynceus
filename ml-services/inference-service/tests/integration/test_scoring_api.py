"""Integration tests: full HTTP request -> response cycle via httpx.AsyncClient
against the real FastAPI app, per AGENTS.md's Python testing conventions.
"""

from __future__ import annotations

from uuid import uuid4

import httpx
import pytest

AUTH_HEADERS = {"Authorization": "Bearer test-token"}
TENANT_ID = "tenant-a"


def _score_request_body(*, tenant_id: str = TENANT_ID, amount: str = "125.50") -> dict[str, object]:
    return {
        "transaction_id": str(uuid4()),
        "tenant_id": tenant_id,
        "customer_id": str(uuid4()),
        "amount": amount,
        "merchant_category": "grocery",
        "is_online": False,
        "is_foreign": False,
        "channel": "in_store",
        "transaction_timestamp": "2026-08-02T14:30:00Z",
    }


# --- /health ---


async def test_get_health_without_model_returns_degraded(
    client_without_model: httpx.AsyncClient,
) -> None:
    response = await client_without_model.get("/api/v1/scoring/health")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "degraded"
    assert body["model_version"] == "unavailable"


async def test_get_health_with_model_returns_healthy(
    client_with_model: httpx.AsyncClient,
) -> None:
    response = await client_with_model.get("/api/v1/scoring/health")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "healthy"
    assert body["model_version"] == "test-v1"


async def test_get_health_requires_no_auth_header(
    client_without_model: httpx.AsyncClient,
) -> None:
    # security: [] override in the spec -- must succeed with zero headers.
    response = await client_without_model.get("/api/v1/scoring/health")
    assert response.status_code == 200


# --- /score: pre-model-loaded 503 ---


async def test_score_without_model_loaded_returns_503(
    client_without_model: httpx.AsyncClient,
) -> None:
    response = await client_without_model.post(
        "/api/v1/scoring/score",
        json=_score_request_body(),
        headers={**AUTH_HEADERS, "X-Tenant-Id": TENANT_ID},
    )

    assert response.status_code == 503
    assert response.json()["error"]["code"] == "MODEL_NOT_LOADED"


# --- /score: auth / tenant header enforcement ---


async def test_score_without_authorization_header_returns_401(
    client_with_model: httpx.AsyncClient,
) -> None:
    response = await client_with_model.post(
        "/api/v1/scoring/score",
        json=_score_request_body(),
        headers={"X-Tenant-Id": TENANT_ID},
    )

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "UNAUTHORIZED"


async def test_score_without_tenant_header_returns_400(
    client_with_model: httpx.AsyncClient,
) -> None:
    response = await client_with_model.post(
        "/api/v1/scoring/score", json=_score_request_body(), headers=AUTH_HEADERS
    )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"


async def test_score_with_mismatched_tenant_returns_403(
    client_with_model: httpx.AsyncClient,
) -> None:
    response = await client_with_model.post(
        "/api/v1/scoring/score",
        json=_score_request_body(tenant_id="tenant-b"),
        headers={**AUTH_HEADERS, "X-Tenant-Id": TENANT_ID},
    )

    assert response.status_code == 403
    assert response.json()["error"]["code"] == "TENANT_MISMATCH"


# --- /score: happy path ---


async def test_score_with_valid_request_returns_200_and_score(
    client_with_model: httpx.AsyncClient,
) -> None:
    body = _score_request_body()

    response = await client_with_model.post(
        "/api/v1/scoring/score",
        json=body,
        headers={**AUTH_HEADERS, "X-Tenant-Id": TENANT_ID},
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["transaction_id"] == body["transaction_id"]
    assert payload["tenant_id"] == TENANT_ID
    assert 0.0 <= payload["isolation_forest_score"] <= 1.0
    assert payload["risk_level"] in {"low", "medium", "high", "critical"}
    assert payload["model_version"] == "test-v1"
    assert isinstance(payload["feature_vector"], dict)


@pytest.mark.parametrize("amount", ["0", "-1"])
async def test_score_with_invalid_amount_returns_400(
    client_with_model: httpx.AsyncClient, amount: str
) -> None:
    # amount=0 is valid (minimum: 0 is inclusive per the spec); amount=-1 is
    # not. Parametrized together to document that boundary explicitly.
    body = _score_request_body(amount=amount)

    response = await client_with_model.post(
        "/api/v1/scoring/score",
        json=body,
        headers={**AUTH_HEADERS, "X-Tenant-Id": TENANT_ID},
    )

    if amount == "0":
        assert response.status_code == 200
    else:
        assert response.status_code == 400


# --- /batch ---


async def test_batch_with_valid_requests_returns_scores_in_order(
    client_with_model: httpx.AsyncClient,
) -> None:
    bodies = [_score_request_body(amount=amount) for amount in ("10.00", "20.00", "30.00")]

    response = await client_with_model.post(
        "/api/v1/scoring/batch",
        json=bodies,
        headers={**AUTH_HEADERS, "X-Tenant-Id": TENANT_ID},
    )

    assert response.status_code == 200
    payload = response.json()
    assert len(payload) == len(bodies)
    assert [item["transaction_id"] for item in payload] == [b["transaction_id"] for b in bodies]
    assert all(0.0 <= item["isolation_forest_score"] <= 1.0 for item in payload)


async def test_batch_without_model_loaded_returns_503(
    client_without_model: httpx.AsyncClient,
) -> None:
    response = await client_without_model.post(
        "/api/v1/scoring/batch",
        json=[_score_request_body()],
        headers={**AUTH_HEADERS, "X-Tenant-Id": TENANT_ID},
    )

    assert response.status_code == 503
    assert response.json()["error"]["code"] == "MODEL_NOT_LOADED"
