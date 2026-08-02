"""Request/response schemas for the scoring API.

Field-for-field mirror of `ScoreTransactionRequest` / `ScoreTransactionResponse`
in api-specs/shared/schemas/fraud-score.yaml, and the `merchant_category` /
`channel` enums in api-specs/shared/schemas/transaction.yaml. This is the
implementation of an already-merged, API-first contract — do not add or
rename fields here without updating the OpenAPI spec first.
"""

from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from enum import StrEnum
from typing import Any
from uuid import UUID

from pydantic import BaseModel, Field


class MerchantCategory(StrEnum):
    """Fixed category list — must match transaction.yaml's `merchant_category` enum
    exactly, since ordinal encoding in feature_engineer.py depends on this exact set.
    """

    GROCERY = "grocery"
    ELECTRONICS = "electronics"
    GAS_STATION = "gas_station"
    RESTAURANT = "restaurant"
    ONLINE_SHOPPING = "online_shopping"
    TRAVEL = "travel"
    ENTERTAINMENT = "entertainment"
    HEALTHCARE = "healthcare"
    UTILITIES = "utilities"
    CLOTHING = "clothing"


class Channel(StrEnum):
    """Transaction channel — must match transaction.yaml's `channel` enum exactly."""

    ONLINE = "online"
    IN_STORE = "in_store"
    ATM = "atm"
    MOBILE = "mobile"


class RiskLevel(StrEnum):
    """Risk tier derived from the ensemble score. Order matters for threshold logic
    in scoring_service.py: LOW < MEDIUM < HIGH < CRITICAL.
    """

    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"


class ScoreTransactionRequest(BaseModel):
    """Raw transaction attributes submitted for scoring.

    `tenant_id` is required directly on the body (not inferred solely from
    the `X-Tenant-Id` header) per the review fix in commit ce0d398 — the
    inference service needs it to populate the tenant_id column on any
    persisted FraudScore record downstream.
    """

    transaction_id: UUID
    tenant_id: str
    customer_id: UUID
    amount: Decimal = Field(ge=0)
    merchant_category: MerchantCategory
    is_online: bool
    is_foreign: bool
    channel: Channel
    transaction_timestamp: datetime


class ScoreTransactionResponse(BaseModel):
    """Fraud score computed synchronously for a single transaction."""

    transaction_id: UUID
    tenant_id: str
    isolation_forest_score: float = Field(ge=0, le=1)
    ensemble_score: float = Field(ge=0, le=1)
    risk_level: RiskLevel
    model_version: str
    feature_vector: dict[str, Any]
    scored_at: datetime


__all__ = [
    "Channel",
    "MerchantCategory",
    "RiskLevel",
    "ScoreTransactionRequest",
    "ScoreTransactionResponse",
]
