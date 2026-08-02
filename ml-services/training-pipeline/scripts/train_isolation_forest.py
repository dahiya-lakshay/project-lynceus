"""Trains the Isolation Forest fraud model on synthetic transaction data.

Feature engineering and score normalization are both imported directly from
the inference service (`inference_service.models`) rather than reimplemented
here — see that package's feature_engineer.py module docstring. Doing this
any other way (e.g. copy-pasting the ordinal category encoding or the sigmoid
formula into this script) would let training and serving silently drift apart
the moment either file is edited without the other, which is exactly the
train/serve skew bug class this reuse is meant to prevent.

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
from sklearn.ensemble import IsolationForest
from sklearn.metrics import f1_score, precision_score, recall_score, roc_auc_score
from sklearn.model_selection import train_test_split

from inference_service.models import FEATURE_COLUMNS, IsolationForestModel, extract_features
from inference_service.schemas.scoring import Channel, MerchantCategory, ScoreTransactionRequest

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


def evaluate(
    estimator: IsolationForest, model_version: str, x_test: np.ndarray, y_test: np.ndarray
) -> dict[str, float]:
    """Scores `x_test` through the real IsolationForestModel.predict() (not a
    reimplemented sigmoid) and computes precision/recall/F1 at
    _CLASSIFICATION_THRESHOLD plus threshold-independent ROC-AUC.
    """
    wrapped = IsolationForestModel(estimator=estimator, version=model_version)
    scores = np.array([wrapped.predict(x_test[i]) for i in range(len(x_test))])
    predictions = (scores >= _CLASSIFICATION_THRESHOLD).astype(int)

    return {
        "precision": float(precision_score(y_test, predictions, zero_division=0)),
        "recall": float(recall_score(y_test, predictions, zero_division=0)),
        "f1": float(f1_score(y_test, predictions, zero_division=0)),
        "roc_auc": float(roc_auc_score(y_test, scores)),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=_DEFAULT_INPUT_PATH, help="Synthetic transactions CSV")
    parser.add_argument("--model-output", type=Path, default=_DEFAULT_MODEL_OUTPUT_PATH)
    parser.add_argument("--feature-columns-output", type=Path, default=_DEFAULT_FEATURE_COLUMNS_OUTPUT_PATH)
    parser.add_argument(
        "--model-version",
        type=str,
        default="isolation-forest-v1",
        help="Version tag recorded alongside the model (matches LYNCEUS_INFERENCE_MODEL_VERSION at serve time)",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()

    print(f"Loading {args.input} ...")
    dataframe = pd.read_csv(args.input)
    if "is_fraud" not in dataframe.columns:
        raise ValueError(
            "Input CSV is missing 'is_fraud' — this script expects generate_synthetic_data.py's "
            "output, not a real transactions export (which has no fraud label)."
        )

    print(f"Extracting features for {len(dataframe)} rows via inference_service.models.extract_features ...")
    features = build_feature_matrix(dataframe)
    labels = dataframe["is_fraud"].astype(int).to_numpy()

    x_train, x_test, y_train, y_test = train_test_split(
        features,
        labels,
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

    metrics = evaluate(estimator, args.model_version, x_test, y_test)

    print("\n=== Evaluation (held-out test set, n={} rows, {} fraud) ===".format(len(y_test), int(y_test.sum())))
    print(f"  Precision @ {_CLASSIFICATION_THRESHOLD}: {metrics['precision']:.4f}")
    print(f"  Recall    @ {_CLASSIFICATION_THRESHOLD}: {metrics['recall']:.4f}")
    print(f"  F1        @ {_CLASSIFICATION_THRESHOLD}: {metrics['f1']:.4f}")
    print(f"  ROC-AUC                : {metrics['roc_auc']:.4f}")

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
