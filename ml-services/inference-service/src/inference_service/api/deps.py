"""FastAPI dependency providers: settings, loaded model, scoring service, redis,
tenant identity, and the Phase 1 auth stub.
"""

from __future__ import annotations

from functools import lru_cache
from typing import Annotated

import redis.asyncio as redis
from fastapi import Depends, Header, HTTPException, Request

from inference_service.core.config import Settings
from inference_service.models.isolation_forest import IsolationForestModel
from inference_service.services.scoring_service import ScoringService


@lru_cache
def get_settings() -> Settings:
    """Settings are immutable env-derived config — cached so every request
    reuses the same instance instead of re-parsing the environment.
    """
    return Settings()


def get_model(request: Request) -> IsolationForestModel | None:
    """None until Task 10's training script produces a model artifact and the
    service is restarted (or a future hot-reload endpoint picks it up).
    """
    return request.app.state.model  # type: ignore[no-any-return]


def get_redis_client(request: Request) -> redis.Redis:
    return request.app.state.redis  # type: ignore[no-any-return]


def get_scoring_service(
    settings: Annotated[Settings, Depends(get_settings)],
    model: Annotated[IsolationForestModel | None, Depends(get_model)],
) -> ScoringService:
    return ScoringService(model=model, model_path=settings.model_path, settings=settings)


async def get_tenant_id(x_tenant_id: Annotated[str, Header(alias="X-Tenant-Id")]) -> str:
    """Required on both scoring endpoints per api-specs/inference-api.yaml's
    TenantIdHeader parameter. FastAPI returns 422 for a missing required
    header, which our RequestValidationError handler maps to 400 (BadRequest)
    to match the spec's documented response.
    """
    return x_tenant_id


async def require_bearer_token(
    authorization: Annotated[str | None, Header(alias="Authorization")] = None,
) -> None:
    """Phase 1 auth stub.

    The spec declares `BearerAuth` (JWT) security on /score and /batch, and
    AGENTS.md's Security section makes NGINX-at-the-gateway responsible for
    real JWT validation (signature, expiry, claims) once Keycloak is wired
    up in Phase 2 — this service is meant to trust the gateway, not
    re-implement JWT verification. Building a signature check here now,
    against no real issuer/JWKS, would be security theater: it would look
    like validation without providing any actual guarantee. Until Phase 2
    lands, this only enforces that a Bearer token is present and non-empty,
    so requests missing the header entirely (e.g. bypassing the gateway in
    dev) are still rejected with 401.
    """
    if authorization is None or not authorization.strip():
        raise HTTPException(
            status_code=401,
            detail={"code": "UNAUTHORIZED", "message": "Missing Authorization header."},
        )
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise HTTPException(
            status_code=401,
            detail={
                "code": "UNAUTHORIZED",
                "message": "Authorization header must be a well-formed Bearer token.",
            },
        )


__all__ = [
    "get_model",
    "get_redis_client",
    "get_scoring_service",
    "get_settings",
    "get_tenant_id",
    "require_bearer_token",
]
