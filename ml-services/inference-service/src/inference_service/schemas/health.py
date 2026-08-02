"""Schema for the unauthenticated scoring health check.

Mirrors the `/api/v1/scoring/health` 200 response schema in
api-specs/inference-api.yaml exactly: both `status` and `model_version` are
required, and `model_version` is a plain (non-nullable) string. When no
model is loaded we therefore report a sentinel string rather than null —
see NOT_LOADED_SENTINEL — to stay contract-compliant while still letting
`status` communicate degraded service.
"""

from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel


class HealthStatus(StrEnum):
    HEALTHY = "healthy"
    DEGRADED = "degraded"


# WHY a sentinel instead of None: the OpenAPI spec declares model_version as
# a required, non-nullable string. Returning null here would be a silent
# contract violation for any strictly-typed client generated from the spec.
NOT_LOADED_SENTINEL = "unavailable"


class HealthResponse(BaseModel):
    status: HealthStatus
    model_version: str


__all__ = ["NOT_LOADED_SENTINEL", "HealthResponse", "HealthStatus"]
