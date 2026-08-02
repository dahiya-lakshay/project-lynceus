"""HTTP route handlers, grouped by resource."""

from inference_service.api.routes.health import router as health_router
from inference_service.api.routes.scoring import router as scoring_router

__all__ = ["health_router", "scoring_router"]
