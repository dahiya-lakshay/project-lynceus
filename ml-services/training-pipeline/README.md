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

## Fraud patterns

Each pattern is described in `configs/data_generation.yaml`'s `fraud_patterns`
block and implemented as its own generator function in
`generate_synthetic_data.py`:

| Pattern | Signal |
|---|---|
| `high_amount` | Amount 5-10x the customer's historical mean |
| `velocity_burst` | 5+ transactions for one customer within a 1-hour window |
| `geographic_impossibility` | Two transactions >1000km apart within 1 hour (haversine) |
| `category_anomaly` | A merchant category the customer has never used |
| `late_night` | A cluster of transactions between 2-5 AM |

## `is_fraud` is not a database column

The generated CSV includes an `is_fraud` label for training/evaluation only.
The real `transactions` table has no such column — fraud is unknown at
ingestion time in production, which is exactly why the model is unsupervised.
`seed_database.py` excludes `is_fraud` (and the DB-generated `amount_bucket`
column) from what it writes to Postgres.

## Connecting to Postgres

`seed_database.py` reads `POSTGRES_HOST` / `POSTGRES_PORT` / `POSTGRES_DB` /
`POSTGRES_USER` / `POSTGRES_PASSWORD` from the environment, defaulting to the
same dev values as `infrastructure/docker/.env.example` (with `POSTGRES_HOST`
defaulting to `localhost`, since this script runs on the host against the
port Compose publishes, not inside the Compose network).
