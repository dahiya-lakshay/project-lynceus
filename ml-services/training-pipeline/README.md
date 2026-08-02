# training-pipeline

Synthetic transaction data generator and Isolation Forest training pipeline for
Lynceus fraud detection. This package doesn't run as a service — it's a set of
scripts that produce two things the ML Inference Service (`ml-services/inference-service`)
needs but can't produce itself: labeled training data, and a trained model
artifact.

## Why this exists

The inference service (Task 7) ships with a scoring API but no trained model —
`IsolationForest.decision_function` needs to be fit on data before it can
score anything. Real transaction history doesn't exist yet either. This
package bridges both gaps: it generates statistically plausible synthetic
transactions (normal spending patterns plus five labeled fraud patterns), then
trains and evaluates an Isolation Forest against them.

## How feature engineering is shared with the inference service

`scripts/train_isolation_forest.py` imports `FEATURE_COLUMNS`, `extract_features`,
and `IsolationForestModel` directly from `inference_service.models` (see
`pyproject.toml`'s `lynceus-inference-service` path dependency) instead of
re-deriving the feature order, ordinal category encoding, or sigmoid score
normalization here. A trained model's feature contract is only valid if
training and serving compute features identically — importing the real module
makes that guaranteed rather than merely intended. See
`ml-services/inference-service/src/inference_service/models/feature_engineer.py`'s
module docstring for the reasoning from the serving side.

## Setup

```bash
uv venv
source .venv/bin/activate
uv pip install -e ".[dev]"
```

This also installs `inference-service` (editable, via the path dependency),
so `import inference_service` resolves to the sibling checkout.

## Running the pipeline end to end

```bash
# 1. Generate synthetic data (writes data/synthetic_transactions.csv, git-ignored)
python scripts/generate_synthetic_data.py --config configs/data_generation.yaml

# 2. Train + evaluate the Isolation Forest, writing model artifacts into
#    ../inference-service/models/
python scripts/train_isolation_forest.py

# 3. Seed a running Postgres instance with the generated transactions
#    (requires `make infra-up` from the repo root first)
python scripts/seed_database.py
```

`generate_synthetic_data.py --seed-db` combines steps 1 and 3 in one
invocation — this is what `make db-seed` calls from the repo root.

For fast local iteration, override the config's scale without editing the
YAML:

```bash
python scripts/generate_synthetic_data.py --num-customers 20 --num-transactions 2000
```

## Tests

```bash
pytest tests/ -v
```

Unit tests cover the fraud-pattern generator functions in
`generate_synthetic_data.py` — asserting the *raw* values they produce
(amount, timestamp clustering, haversine distance, category membership)
really do exhibit the anomalous property each pattern name claims, not just
that the functions run without error. They deliberately don't test whether
the current Phase 1 feature set can *detect* those patterns after feature
extraction — that's a separate, measured concern (see `train_isolation_forest.py`'s
module docstring and the Fraud patterns section below).

## Fraud patterns

Each pattern is described in `configs/data_generation.yaml`'s `fraud_patterns`
block and implemented as its own generator function in
`generate_synthetic_data.py`:

| Pattern | Signal | Detected by the Phase 1 model? |
|---|---|---|
| `high_amount` | Amount 5-10x the customer's historical mean | Yes — recall ≈ 0.82 |
| `geographic_impossibility` | Two transactions >1000km apart within 1 hour (haversine) | Yes — recall ≈ 0.82, via `is_foreign` |
| `late_night` | A cluster of transactions between 2-5 AM | Barely — recall ≈ 0.03 |
| `velocity_burst` | 5+ transactions for one customer within a 1-hour window | No — recall ≈ 0.06 |
| `category_anomaly` | A merchant category the customer has never used | No — recall ≈ 0.00 |

**Why three of these aren't reliably detected right now:** `FEATURE_COLUMNS`
(defined in the inference service, reused as-is — see above) is Phase 1's
deliberately minimal feature set: amount, amount_log, hour/day-of-week
cyclical encodings, is_online, is_foreign, merchant_category. There is no
location/distance feature, no transaction-velocity/count feature, and no
per-customer-history feature — those require the Customer Profile Service
(Phase 2) and the Redis Feature Store (Phase 3), which are out of scope for
this branch per CLAUDE.md ("don't introduce Phase 3 dependencies into Phase
1 code"). `category_anomaly` and `velocity_burst` have literally no
representation in the feature set and land at the normal-transaction
false-positive rate. `geographic_impossibility` reaches the model only
through `is_foreign`, but that proxy turns out to work well in practice
because genuine foreign transactions are rare in the synthetic data.
`late_night` has a nominal feature (`hour_sin`/`hour_cos`) but still isn't
well detected in practice, since 2-5 AM traffic is rare-but-not-absent for
normal transactions and every other feature on a late-night fraud row looks
ordinary.

These three patterns stay in the synthetic data anyway — they're realistic
signal to have on hand once Phase 2/3 features exist, and their presence
doesn't hurt the patterns that *are* learnable now. But don't read the
aggregate ROC-AUC this script prints as "the model catches all five
patterns" — it doesn't. Run `python scripts/train_isolation_forest.py` and
read its `=== Per-pattern breakdown ===` output for current, actual
per-pattern numbers (recall for each fraud pattern, false-positive rate for
normal transactions) — the numbers above are a snapshot from one full-scale
run and will drift as the data or model changes.

## `is_fraud` and `fraud_pattern` are not database columns

The generated CSV includes `is_fraud` (boolean label) and `fraud_pattern`
(which of the five patterns produced the row, or `"normal"`) for
training/evaluation only. The real `transactions` table has neither column —
fraud is unknown at ingestion time in production, which is exactly why the
model is unsupervised. `seed_database.py` excludes both (and the DB-generated
`amount_bucket` column) from what it writes to Postgres.

## Connecting to Postgres

`seed_database.py` reads `POSTGRES_HOST` / `POSTGRES_PORT` / `POSTGRES_DB` /
`POSTGRES_USER` / `POSTGRES_PASSWORD` from the environment, defaulting to the
same dev values as `infrastructure/docker/.env.example` (with `POSTGRES_HOST`
defaulting to `localhost`, since this script runs on the host against the
port Compose publishes, not inside the Compose network).
