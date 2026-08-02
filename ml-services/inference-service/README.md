# ML Inference Service

Computes fraud scores for transactions. Phase 1 ships a single Isolation
Forest model; an Autoencoder ensemble member is planned for a later task.
The authoritative API contract is `api-specs/inference-api.yaml` and
`api-specs/shared/schemas/fraud-score.yaml` — this service implements that
contract, not the other way around.

## Endpoints

| Method | Path                        | Auth                          |
|--------|-----------------------------|--------------------------------|
| POST   | `/api/v1/scoring/score`     | Bearer token (presence check, Phase 1) + `X-Tenant-Id` |
| POST   | `/api/v1/scoring/batch`     | Bearer token (presence check, Phase 1) + `X-Tenant-Id` |
| GET    | `/api/v1/scoring/health`    | None                           |

## No model yet? That's expected.

Until Task 10's training pipeline runs and produces
`models/isolation_forest.joblib` (git-ignored — see `.gitignore`'s `*.joblib`
entry), the service still starts normally. `/health` reports
`{"status": "degraded", "model_version": "unavailable"}`, and `/score` /
`/batch` return `503` with `code: MODEL_NOT_LOADED` rather than crashing or
returning a fabricated score.

## Local development

```bash
uv venv && source .venv/bin/activate
uv pip install -e ".[dev]"

pytest tests/ -v
ruff check .
mypy src/

uvicorn src.inference_service.main:app --reload --port 8082
```

## Auth model (Phase 1)

`api-specs/inference-api.yaml` declares `BearerAuth` (JWT) security on the
scoring endpoints. Per AGENTS.md's Security section, real JWT validation
(signature, expiry, claims) is NGINX's job at the gateway once Keycloak is
integrated in Phase 2 — this service is meant to trust that gateway, not
duplicate JWT verification against no real issuer. `require_bearer_token`
in `api/deps.py` is a deliberate, documented stub: it only checks a
well-formed `Bearer <token>` header is present, so requests that bypass the
gateway entirely in dev still get a 401 instead of silently proceeding
unauthenticated. It does **not** validate the token's signature or claims —
do not mistake it for real auth.

## Score normalization

scikit-learn's `IsolationForest.decision_function` is unbounded and
training-run-specific (high = normal, low/negative = anomalous). Without a
persisted min/max from the training run that produced the model — Task 10
hasn't shipped that yet — `IsolationForestModel.predict` maps the raw score
into `[0, 1]` via a fixed-steepness sigmoid on the negated raw score
(`isolation_forest.py` has the full rationale). It's monotonic, so
`risk_level` bucketing is stable, but it is a heuristic scale, not a
calibrated probability; revisit once Task 10 can persist true training-time
score bounds alongside the model artifact.

## Feature order

`models/feature_engineer.py`'s `FEATURE_COLUMNS` fixes the exact feature
vector order the model is scored on. **Task 10's training script must import
`FEATURE_COLUMNS` and `extract_features` from this module** rather than
re-implementing the same logic, or training and serving will silently drift
apart.
