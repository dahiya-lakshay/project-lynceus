"""Per-request logging context: binds tenant_id/request_id/trace_id so every
structlog log line emitted while handling a request carries them, per
AGENTS.md: "Include tenant_id, request_id, trace_id in all log entries."
"""

from __future__ import annotations

import uuid
from typing import TYPE_CHECKING

import structlog
from starlette.middleware.base import BaseHTTPMiddleware

if TYPE_CHECKING:
    from starlette.middleware.base import RequestResponseEndpoint
    from starlette.requests import Request
    from starlette.responses import Response
    from starlette.types import ASGIApp


class RequestContextMiddleware(BaseHTTPMiddleware):
    """Binds request-scoped identifiers into structlog's contextvars for the
    duration of the request, and stamps them on `request.state` so exception
    handlers (which run outside this middleware's own log statements) can
    still include the trace_id in the ErrorResponse body.
    """

    def __init__(self, app: ASGIApp) -> None:
        super().__init__(app)

    async def dispatch(
        self, request: Request, call_next: RequestResponseEndpoint
    ) -> Response:
        # Trust an inbound X-Request-Id / traceparent from the gateway if
        # present (preserves correlation across service hops); otherwise
        # mint one so every request is still traceable in isolation.
        request_id = request.headers.get("X-Request-Id", str(uuid.uuid4()))
        trace_id = request.headers.get("traceparent") or request_id
        tenant_id = request.headers.get("X-Tenant-Id")

        request.state.request_id = request_id
        request.state.trace_id = trace_id

        structlog.contextvars.bind_contextvars(
            request_id=request_id,
            trace_id=trace_id,
            tenant_id=tenant_id,
        )
        try:
            response: Response = await call_next(request)
        finally:
            # Contextvars are task-scoped, not global — clearing here still
            # matters because the ASGI server may reuse the same task/thread
            # context for a subsequent request.
            structlog.contextvars.clear_contextvars()

        response.headers["X-Request-Id"] = request_id
        return response


__all__ = ["RequestContextMiddleware"]
