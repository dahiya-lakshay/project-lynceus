"""POST /api/v1/scoring/score and POST /api/v1/scoring/batch.

Routes stay thin per AGENTS.md's Python conventions — validation and
delegation only, all business logic lives in ScoringService.
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException

from inference_service.api.deps import get_scoring_service, get_tenant_id, require_bearer_token
from inference_service.schemas.scoring import ScoreTransactionRequest, ScoreTransactionResponse
from inference_service.services.scoring_service import ScoringService

router = APIRouter(dependencies=[Depends(require_bearer_token)])


def _ensure_tenant_matches(header_tenant_id: str, payload_tenant_id: str) -> None:
    """Defense-in-depth check: the body's `tenant_id` (added in review fix
    ce0d398) and the `X-Tenant-Id` header must agree. A mismatch means the
    caller (Transaction Service / Flink job) is scoping the request to a
    different tenant than it's authenticated for — reject rather than
    silently trusting whichever value happens to be more convenient,
    per AGENTS.md: "Every database query MUST filter by tenant_id."
    """
    if header_tenant_id != payload_tenant_id:
        raise HTTPException(
            status_code=403,
            detail={
                "code": "TENANT_MISMATCH",
                "message": (
                    f"X-Tenant-Id header ({header_tenant_id!r}) does not match "
                    f"request body tenant_id ({payload_tenant_id!r})."
                ),
            },
        )


@router.post("/score", response_model=ScoreTransactionResponse)
async def score_transaction(
    payload: ScoreTransactionRequest,
    tenant_id: Annotated[str, Depends(get_tenant_id)],
    scoring_service: Annotated[ScoringService, Depends(get_scoring_service)],
) -> ScoreTransactionResponse:
    _ensure_tenant_matches(tenant_id, payload.tenant_id)
    return await scoring_service.score(payload)


@router.post("/batch", response_model=list[ScoreTransactionResponse])
async def score_transactions_batch(
    payload: list[ScoreTransactionRequest],
    tenant_id: Annotated[str, Depends(get_tenant_id)],
    scoring_service: Annotated[ScoringService, Depends(get_scoring_service)],
) -> list[ScoreTransactionResponse]:
    for request in payload:
        _ensure_tenant_matches(tenant_id, request.tenant_id)
    return await scoring_service.score_batch(payload)


__all__ = ["router"]
