"""Custom exceptions for the ML Inference Service.

Domain errors are raised as typed exceptions rather than ad-hoc
`HTTPException` calls scattered through the service layer, so the service
layer stays framework-agnostic and the API layer (main.py exception
handlers) owns the single place HTTP status codes are decided. Never caught
as bare `Exception` — always caught by their specific type or by these
handlers.
"""

from __future__ import annotations


class InferenceServiceError(Exception):
    """Base class for all domain errors raised by the inference service.

    Carries a machine-readable `code` so API responses match the shared
    ErrorResponse contract (api-specs/shared/errors.yaml) uniformly across
    every Lynceus service, not just this one.
    """

    def __init__(self, message: str, *, code: str) -> None:
        self.message = message
        self.code = code
        super().__init__(message)


class ModelNotLoadedError(InferenceServiceError):
    """Raised when a scoring request arrives before a model has been loaded.

    Expected in normal operation until Task 10's training script produces
    `models/isolation_forest.joblib` — this must map to 503 Service
    Unavailable, not 500, since it's a transient upstream-dependency state
    rather than a bug.
    """

    def __init__(self) -> None:
        super().__init__(
            "Fraud scoring model is not loaded. The service is running in "
            "degraded mode until a trained model artifact is available.",
            code="MODEL_NOT_LOADED",
        )


class FeatureExtractionError(InferenceServiceError):
    """Raised when transaction attributes cannot be converted to a feature vector.

    Maps to 422 Unprocessable Entity — the request was syntactically valid
    (passed Pydantic validation) but semantically unscoreable, e.g. an
    out-of-range timestamp.
    """

    def __init__(self, message: str) -> None:
        super().__init__(message, code="FEATURE_EXTRACTION_FAILED")


__all__ = ["FeatureExtractionError", "InferenceServiceError", "ModelNotLoadedError"]
