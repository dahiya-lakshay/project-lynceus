"""Pydantic v2 request/response schemas for the ML Inference Service API."""

from inference_service.schemas.error import ErrorDetail, ErrorResponse
from inference_service.schemas.health import HealthResponse, HealthStatus
from inference_service.schemas.scoring import (
    Channel,
    MerchantCategory,
    RiskLevel,
    ScoreTransactionRequest,
    ScoreTransactionResponse,
)

__all__ = [
    "Channel",
    "ErrorDetail",
    "ErrorResponse",
    "HealthResponse",
    "HealthStatus",
    "MerchantCategory",
    "RiskLevel",
    "ScoreTransactionRequest",
    "ScoreTransactionResponse",
]
