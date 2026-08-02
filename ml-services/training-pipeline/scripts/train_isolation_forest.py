"""Trains the Isolation Forest fraud model on synthetic transaction data.

Feature engineering and score normalization are both imported directly from
the inference service (`inference_service.models`) rather than reimplemented
here — see that package's feature_engineer.py module docstring. Doing this
any other way (e.g. copy-pasting the ordinal category encoding or the sigmoid
formula into this script) would let training and serving silently drift apart
the moment either file is edited without the other, which is exactly the
train/serve skew bug class this reuse is meant to prevent.

IMPORTANT — Phase 1 feature-set limitation: `FEATURE_COLUMNS` (amount,
amount_log, hour_sin/cos, day_of_week_sin/cos, is_online, is_foreign,
merchant_category) has no location/distance feature, no transaction-velocity
feature, and no per-customer-history feature. Those are Phase 2 (Customer
Profile Service) / Phase 3 (Redis Feature Store) work per the implementation
plan — deliberately out of scope here (CLAUDE.md: "don't introduce Phase 3
dependencies into Phase 1 code"). `category_anomaly` and `velocity_burst`
have zero representation in FEATURE_COLUMNS, and `geographic_impossibility`
only reaches the model through the `is_foreign` proxy; `late_night` only
reaches it through `hour_sin`/`hour_cos`.

A feature *existing* doesn't guarantee the pattern is actually detected,
though — measure, don't assume. A full-scale run (100K rows, default config,
threshold 0.5) produced:

    pattern                     recall (or FPR for normal)
    normal                      0.0119  (false positive rate)
    high_amount                 0.8167
    geographic_impossibility    0.8228
    late_night                  0.0286
    velocity_burst              0.0556
    category_anomaly            0.0000

`category_anomaly` and `velocity_burst` land at/near the normal-transaction
false-positive rate, exactly as expected from having no feature
representation at all. `geographic_impossibility` turns out to be detected
well in practice — `is_foreign=True` is rare enough among genuine
transactions (~3% of travel-category rows) that the model effectively learns
it as a strong anomaly signal on its own, not just a "weak proxy" as it might
first appear. `late_night`, despite having a nominal feature
(`hour_sin`/`hour_cos`), is barely above the false-positive rate — the
diurnal weighting in generate_synthetic_data.py makes 2-5 AM traffic rare but
not vanishingly so for normal transactions, and IsolationForest's global
per-tree partitioning doesn't isolate a purely-time-based outlier well when
every other feature (amount, category, is_online) looks completely ordinary.
The per-pattern breakdown this script prints (see `evaluate_by_pattern`)
makes gaps like this visible on every run instead of letting a single
aggregate ROC-AUC hide them — re-run this script after any config or feature
change to get current numbers; don't trust the table above to stay accurate.

Usage:
    python train_isolation_forest.py \\
        --input data/synthetic_transactions.csv \\
        --model-output ../inference-service/models/isolation_forest.joblib \\
        --feature-columns-output ../inference-service/models/feature_columns.json
"""

from __future__ import annotations

import argparse
import json
from decimal import Decimal
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from generate_synthetic_data import ALL_PATTERNS, PATTERN_NORMAL
from inference_service.models import FEATURE_COLUMNS, IsolationForestModel, extract_features
from inference_service.schemas.scoring import Channel, MerchantCategory, ScoreTransactionRequest
from sklearn.ensemble import IsolationForest
from sklearn.metrics import f1_score, precision_score, recall_score, roc_auc_score
from sklearn.model_selection import train_test_split

_SCRIPT_DIR = Path(__file__).resolve().parent
_PACKAGE_DIR = _SCRIPT_DIR.parent
_DEFAULT_INPUT_PATH = _PACKAGE_DIR / "data" / "synthetic_transactions.csv"
_INFERENCE_MODELS_DIR = _PACKAGE_DIR.parent / "inference-service" / "models"
_DEFAULT_MODEL_OUTPUT_PATH = _INFERENCE_MODELS_DIR / "isolation_forest.joblib"
_DEFAULT_FEATURE_COLUMNS_OUTPUT_PATH = _INFERENCE_MODELS_DIR / "feature_columns.json"

# Estimator hyperparameters and the train/test split ratio are fixed per the
# Task 10 spec rather than exposed as CLI flags — this is a one-shot training
# script for the Phase 1 MVP model, not a hyperparameter search harness.
_N_ESTIMATORS = 200
_CONTAMINATION = 0.02
_RANDOM_STATE = 42
_TEST_SIZE = 0.2
# Classification threshold applied to the [0, 1] normalized score for
# precision/recall/F1 (which need a hard decision). ROC-AUC is
# threshold-independent and is the more informative metric for an anomaly
# detector; 0.5 is used for the binary metrics purely as the natural midpoint
# of the same [0, 1] scale scoring_service.py's risk_level buckets sit on.
_CLASSIFICATION_THRESHOLD = 0.5


def _row_to_request(row: pd.Series) -> ScoreTransactionRequest:
    """Rebuilds the exact request object extract_features() expects from one
    synthetic CSV row. Going through ScoreTransactionRequest (not a bespoke
    dict) guarantees the same Pydantic validation/coercion the live scoring
    API applies runs here too.
    """
    return ScoreTransactionRequest(
        transaction_id=row["id"],
        tenant_id=row["tenant_id"],
        customer_id=row["customer_id"],
        amount=Decimal(str(row["amount"])),
        merchant_category=MerchantCategory(row["merchant_category"]),
        is_online=bool(row["is_online"]),
        is_foreign=bool(row["is_foreign"]),
        channel=Channel(row["channel"]),
        transaction_timestamp=pd.Timestamp(row["created_at"]).to_pydatetime(),
    )


def build_feature_matrix(dataframe: pd.DataFrame) -> np.ndarray:
    """Runs every row through the inference service's extract_features(),
    producing an (n_samples, len(FEATURE_COLUMNS)) matrix in the exact
    feature order the serving code trains and scores on.
    """
    vectors = [extract_features(_row_to_request(row)) for _, row in dataframe.iterrows()]
    return np.vstack(vectors)


def score_test_set(
    estimator: IsolationForest, model_version: str, x_test: np.ndarray
) -> np.ndarray:
    """Scores every row in `x_test` through the real IsolationForestModel.predict()
    (not a reimplemented sigmoid) — see the module docstring's reuse rationale.
    """
    wrapped = IsolationForestModel(estimator=estimator, version=model_version)
    return np.array([wrapped.predict(x_test[i]) for i in range(len(x_test))])


def evaluate(scores: np.ndarray, y_test: np.ndarray) -> dict[str, float]:
    """Aggregate precision/recall/F1 at _CLASSIFICATION_THRESHOLD plus
    threshold-independent ROC-AUC, computed across the whole test set
    regardless of which fraud pattern (if any) produced each row.
    """
    predictions = (scores >= _CLASSIFICATION_THRESHOLD).astype(int)
    return {
        "precision": float(precision_score(y_test, predictions, zero_division=0)),
        "recall": float(recall_score(y_test, predictions, zero_division=0)),
        "f1": float(f1_score(y_test, predictions, zero_division=0)),
        "roc_auc": float(roc_auc_score(y_test, scores)),
    }


def evaluate_by_pattern(
    scores: np.ndarray, pattern_test: np.ndarray
) -> list[dict[str, float | int | str]]:
    """Breaks the aggregate metrics in `evaluate()` down by fraud_pattern.

    A single aggregate ROC-AUC can look respectable while quietly failing on
    specific patterns — see the module docstring's real numbers from a
    full-scale run. This function is what makes each pattern's actual
    detectability visible instead of letting the aggregate number hide it;
    which patterns end up well- or poorly-detected isn't always predictable
    from feature representation alone (geographic_impossibility turns out
    strong via is_foreign; late_night turns out weak despite having
    hour_sin/cos) — that's the point of measuring per-pattern rather than
    assuming from the feature list.

    For fraud patterns, "flagged_rate" is recall (fraction scored >=
    threshold, i.e. correctly caught). For PATTERN_NORMAL it's the false
    positive rate (fraction of genuinely normal transactions incorrectly
    scored >= threshold) — same computation, different label because the
    ground truth being compared against is inverted.
    """
    rows: list[dict[str, float | int | str]] = []
    for pattern in ALL_PATTERNS:
        mask = pattern_test == pattern
        n = int(mask.sum())
        if n == 0:
            rows.append(
                {
                    "pattern": pattern,
                    "n": 0,
                    "mean_score": float("nan"),
                    "flagged_rate": float("nan"),
                }
            )
            continue
        pattern_scores = scores[mask]
        rows.append(
            {
                "pattern": pattern,
                "n": n,
                "mean_score": float(pattern_scores.mean()),
                "flagged_rate": float((pattern_scores >= _CLASSIFICATION_THRESHOLD).mean()),
            }
        )
    return rows


def print_pattern_breakdown(rows: list[dict[str, float | int | str]]) -> None:
    print(f"\n=== Per-pattern breakdown (threshold={_CLASSIFICATION_THRESHOLD}) ===")
    header = f"  {'pattern':<28}{'n':>6}   {'mean_score':>10}   {'flagged_rate':>12}"
    print(header)
    for row in rows:
        label = str(row["pattern"])
        suffix = "  (false positive rate)" if label == PATTERN_NORMAL else "  (recall)"
        n = row["n"]
        if n == 0:
            print(f"  {label:<28}{n:>6}   {'n/a':>10}   {'n/a':>12}{suffix}")
            continue
        mean_score = row["mean_score"]
        flagged_rate = row["flagged_rate"]
        print(f"  {label:<28}{n:>6}   {mean_score:>10.4f}   {flagged_rate:>12.4f}{suffix}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--input", type=Path, default=_DEFAULT_INPUT_PATH, help="Synthetic transactions CSV"
    )
    parser.add_argument("--model-output", type=Path, default=_DEFAULT_MODEL_OUTPUT_PATH)
    parser.add_argument(
        "--feature-columns-output", type=Path, default=_DEFAULT_FEATURE_COLUMNS_OUTPUT_PATH
    )
    parser.add_argument(
        "--model-version",
        type=str,
        default="isolation-forest-v1",
        help="Version tag recorded alongside the model (matches "
        "LYNCEUS_INFERENCE_MODEL_VERSION at serve time)",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()

    print(f"Loading {args.input} ...")
    dataframe = pd.read_csv(args.input)
    if "is_fraud" not in dataframe.columns or "fraud_pattern" not in dataframe.columns:
        raise ValueError(
            "Input CSV is missing 'is_fraud' and/or 'fraud_pattern' — this script expects "
            "generate_synthetic_data.py's output, not a real transactions export (which has "
            "no fraud label)."
        )

    print(f"Extracting features for {len(dataframe)} rows via inference_service extract_features()")
    features = build_feature_matrix(dataframe)
    labels = dataframe["is_fraud"].astype(int).to_numpy()
    patterns = dataframe["fraud_pattern"].astype(str).to_numpy()

    x_train, x_test, y_train, y_test, _, pattern_test = train_test_split(
        features,
        labels,
        patterns,
        test_size=_TEST_SIZE,
        random_state=_RANDOM_STATE,
        stratify=labels,
    )

    print(
        f"Training IsolationForest(n_estimators={_N_ESTIMATORS}, "
        f"contamination={_CONTAMINATION}, max_samples='auto', random_state={_RANDOM_STATE}) "
        f"on {len(x_train)} rows ({int(y_train.sum())} labeled fraud, used only for evaluation, "
        "not for fitting — IsolationForest is unsupervised) ..."
    )
    estimator = IsolationForest(
        n_estimators=_N_ESTIMATORS,
        contamination=_CONTAMINATION,
        max_samples="auto",
        random_state=_RANDOM_STATE,
    )
    estimator.fit(x_train)

    scores = score_test_set(estimator, args.model_version, x_test)
    metrics = evaluate(scores, y_test)

    print(
        f"\n=== Evaluation (held-out test set, n={len(y_test)} rows, {int(y_test.sum())} fraud) ==="
    )
    print(f"  Precision @ {_CLASSIFICATION_THRESHOLD}: {metrics['precision']:.4f}")
    print(f"  Recall    @ {_CLASSIFICATION_THRESHOLD}: {metrics['recall']:.4f}")
    print(f"  F1        @ {_CLASSIFICATION_THRESHOLD}: {metrics['f1']:.4f}")
    print(f"  ROC-AUC                : {metrics['roc_auc']:.4f}")

    # See module docstring: this breakdown is the point — it's what makes
    # the Phase 1 feature-set gap on category_anomaly/velocity_burst/
    # geographic_impossibility visible instead of hidden inside the
    # aggregate numbers above.
    pattern_rows = evaluate_by_pattern(scores, pattern_test)
    print_pattern_breakdown(pattern_rows)

    args.model_output.parent.mkdir(parents=True, exist_ok=True)
    joblib.dump(estimator, args.model_output)
    print(f"\nSaved estimator -> {args.model_output}")

    args.feature_columns_output.parent.mkdir(parents=True, exist_ok=True)
    feature_columns_payload: list[str] = list(FEATURE_COLUMNS)
    with args.feature_columns_output.open("w", encoding="utf-8") as handle:
        json.dump(feature_columns_payload, handle, indent=2)
        handle.write("\n")
    print(f"Saved feature columns -> {args.feature_columns_output}")


if __name__ == "__main__":
    main()
