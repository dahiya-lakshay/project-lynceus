"""GET /api/v1/scoring/health — unauthenticated liveness/readiness probe.

`security: []` in api-specs/inference-api.yaml overrides the spec-wide
BearerAuth requirement for this one operation, so it carries no auth
dependency, unlike scoring.py's routes.
"""

from __future__ import annotations

from fastapi import APIRouter, Request

from inference_service.schemas.health import NOT_LOADED_SENTINEL, HealthResponse, HealthStatus

router = APIRouter()


@router.get("/health", response_model=HealthResponse)
async def get_scoring_health(request: Request) -> HealthResponse:
    """Reports degraded (200, not 503) when no model is loaded.

    A 5xx here would make orchestrators (k8s liveness/readiness probes,
    load balancers) restart or drain an otherwise-healthy instance just
    because Task 10 hasn't trained a model yet — the *process* is fine,
    only the *scoring capability* is degraded, and only the scoring
    endpoints themselves should reflect that via 503.
    """
    model = request.app.state.model
    if model is None:
        return HealthResponse(status=HealthStatus.DEGRADED, model_version=NOT_LOADED_SENTINEL)
    return HealthResponse(status=HealthStatus.HEALTHY, model_version=model.version)


__all__ = ["router"]
