"""FastAPI app factory for the ML Inference Service.

Wires together lifespan model loading, structlog JSON logging, CORS, request
context middleware, routes, and the exception -> HTTP response mapping that
keeps every error response shaped like api-specs/shared/errors.yaml's
ErrorResponse.
"""

from __future__ import annotations

import logging
import sys
from contextlib import asynccontextmanager
from datetime import UTC, datetime
from typing import TYPE_CHECKING, Any

import redis.asyncio as redis
import structlog
from fastapi import FastAPI, Request
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from inference_service.api.deps import get_settings
from inference_service.api.middleware import RequestContextMiddleware
from inference_service.api.routes import health_router, scoring_router
from inference_service.core.exceptions import FeatureExtractionError, ModelNotLoadedError
from inference_service.models.isolation_forest import IsolationForestModel
from inference_service.schemas.error import ErrorDetail, ErrorResponse
from inference_service.services.scoring_service import shutdown_executor

if TYPE_CHECKING:
    from collections.abc import AsyncIterator

logger = structlog.get_logger(__name__)

_HTTP_STATUS_CODES: dict[int, str] = {
    400: "BAD_REQUEST",
    401: "UNAUTHORIZED",
    403: "FORBIDDEN",
    404: "NOT_FOUND",
    409: "CONFLICT",
    422: "UNPROCESSABLE_ENTITY",
    429: "TOO_MANY_REQUESTS",
    503: "SERVICE_UNAVAILABLE",
}


def _configure_logging() -> None:
    """JSON structured logging everywhere, per AGENTS.md's Python conventions.

    stdlib logging is routed through structlog too (uvicorn's access/error
    loggers use stdlib logging directly), so the whole process emits one
    consistent JSON format instead of uvicorn's plain-text lines next to our
    JSON ones.
    """
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso"),
            structlog.processors.StackInfoRenderer(),
            structlog.processors.format_exc_info,
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(logging.INFO),
        logger_factory=structlog.PrintLoggerFactory(file=sys.stdout),
        cache_logger_on_first_use=True,
    )


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Startup: attempt to load the Isolation Forest model; missing is a
    normal, non-fatal state (Task 10 hasn't trained one yet) — the service
    still starts and serves /health as degraded. Any *other* load failure
    (corrupt file, incompatible sklearn version) is not swallowed; it
    propagates and fails startup loudly, since that's an actual bug rather
    than the expected pre-training state.
    """
    settings = get_settings()

    model: IsolationForestModel | None = None
    if settings.model_path.exists():
        model = IsolationForestModel.load(settings.model_path, settings.model_version)
        logger.info(
            "model_loaded",
            model_path=str(settings.model_path),
            model_version=model.version,
        )
    else:
        logger.warning(
            "model_not_found_starting_degraded",
            model_path=str(settings.model_path),
            hint="Run Task 10's training pipeline to produce this artifact.",
        )
    app.state.model = model
    app.state.redis = redis.Redis(
        host=settings.redis_host, port=settings.redis_port, db=settings.redis_db
    )

    try:
        yield
    finally:
        await app.state.redis.aclose()
        shutdown_executor()


def _error_response(
    request: Request,
    *,
    status_code: int,
    code: str,
    message: str,
    details: dict[str, Any] | None = None,
) -> JSONResponse:
    trace_id = getattr(request.state, "trace_id", "unknown")
    body = ErrorResponse(
        error=ErrorDetail(
            code=code,
            message=message,
            details=details,
            trace_id=trace_id,
            timestamp=datetime.now(UTC),
        )
    )
    return JSONResponse(status_code=status_code, content=body.model_dump(mode="json"))


def create_app() -> FastAPI:
    _configure_logging()
    settings = get_settings()

    app = FastAPI(
        title="Lynceus ML Inference Service",
        version="0.1.0",
        lifespan=lifespan,
    )

    # Added first so it's the outermost layer (Starlette wraps middleware in
    # reverse registration order) — CORS headers must reach even error
    # responses raised deeper in the stack, not just successful ones.
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_allowed_origins,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    app.add_middleware(RequestContextMiddleware)

    app.include_router(health_router, prefix="/api/v1/scoring", tags=["Scoring"])
    app.include_router(scoring_router, prefix="/api/v1/scoring", tags=["Scoring"])

    @app.exception_handler(ModelNotLoadedError)
    async def handle_model_not_loaded(request: Request, exc: ModelNotLoadedError) -> JSONResponse:
        # 503, not 500: an unloaded model is a known, transient upstream
        # state (pre-Task-10, or mid-restart), not a bug.
        return _error_response(request, status_code=503, code=exc.code, message=exc.message)

    @app.exception_handler(FeatureExtractionError)
    async def handle_feature_extraction_error(
        request: Request, exc: FeatureExtractionError
    ) -> JSONResponse:
        return _error_response(request, status_code=422, code=exc.code, message=exc.message)

    @app.exception_handler(StarletteHTTPException)
    async def handle_http_exception(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        # Routes/deps raise HTTPException with a {"code", "message"} detail
        # dict (see api/deps.py, api/routes/scoring.py) so the ErrorResponse
        # envelope stays consistent; fall back to a status-derived code for
        # any HTTPException FastAPI/Starlette raises internally (e.g. 404
        # on an unmatched route) that only carries a plain string detail.
        detail = exc.detail
        if isinstance(detail, dict) and "code" in detail and "message" in detail:
            code, message = str(detail["code"]), str(detail["message"])
        else:
            code = _HTTP_STATUS_CODES.get(exc.status_code, f"HTTP_{exc.status_code}")
            message = str(detail)
        return _error_response(request, status_code=exc.status_code, code=code, message=message)

    @app.exception_handler(RequestValidationError)
    async def handle_validation_error(
        request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        # Maps to 400 (BadRequest), matching the "malformed request body or
        # parameters" wording of api-specs/shared/errors.yaml's BadRequest
        # response — FastAPI's own default of 422 is reserved here for our
        # domain-level FeatureExtractionError instead.
        return _error_response(
            request,
            status_code=400,
            code="VALIDATION_ERROR",
            message="The request body or parameters were malformed.",
            details={"errors": jsonable_encoder(exc.errors())},
        )

    return app


app = create_app()

__all__ = ["app", "create_app"]
