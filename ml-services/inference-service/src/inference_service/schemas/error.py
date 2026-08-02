"""Shared error response schema.

Mirrors api-specs/shared/errors.yaml's `ErrorResponse` exactly, so every
error this service returns — validation failures, model-not-loaded, etc. —
has the same shape as every other Lynceus service, per AGENTS.md's "Error
Handling" section.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel


class ErrorDetail(BaseModel):
    code: str
    message: str
    details: dict[str, Any] | None = None
    trace_id: str
    timestamp: datetime


class ErrorResponse(BaseModel):
    error: ErrorDetail


__all__ = ["ErrorDetail", "ErrorResponse"]
