"""Application configuration for the ML Inference Service.

All configuration is sourced from environment variables (with `.env` file
support for local development), per AGENTS.md's "Environment Variables"
section — nothing here is hardcoded.
"""

from __future__ import annotations

from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Service configuration, loaded once and shared via FastAPI DI.

    Env var naming follows AGENTS.md's `LYNCEUS_<SERVICE>_<CONFIG>` convention
    for settings owned by this service. Redis is shared infrastructure used by
    multiple services, so its settings intentionally use the flatter
    `LYNCEUS_REDIS_*` prefix AGENTS.md itself uses as the canonical example,
    rather than being namespaced under INFERENCE — the same Redis instance
    config should look identical regardless of which service reads it.
    """

    model_config = SettingsConfigDict(
        env_prefix="LYNCEUS_INFERENCE_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # --- Model artifact ---
    # Absent until Task 10's training script runs; startup must tolerate that
    # (see main.py lifespan) rather than crash the whole service.
    model_path: Path = Path("models/isolation_forest.joblib")
    model_version: str = "isolation-forest-unversioned"

    # --- HTTP / CORS ---
    port: int = 8082
    # Configurable rather than hardcoded so staging/prod can restrict this to
    # the real dashboard origin without a code change.
    cors_allowed_origins: list[str] = ["http://localhost:3000"]

    # --- Redis (shared infra; see class docstring for naming rationale) ---
    redis_host: str = "localhost"
    redis_port: int = 6379
    redis_db: int = 0

    # --- Inference execution ---
    # CPU-bound sklearn inference must not block the asyncio event loop
    # (AGENTS.md: "Use ProcessPoolExecutor for CPU-bound ML inference").
    # Kept modest by default since each worker also holds a copy of the
    # loaded model in memory.
    process_pool_workers: int = 2

    # --- Risk level thresholds ---
    # Fixed business thresholds from the Task 7 spec. Exposed as settings
    # (not literals in scoring_service.py) so they can be tuned per
    # environment without a code change once real fraud data informs them.
    risk_threshold_low: float = 0.3
    risk_threshold_medium: float = 0.5
    risk_threshold_high: float = 0.7


__all__ = ["Settings"]
