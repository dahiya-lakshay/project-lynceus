"""Bulk-loads the synthetic transactions CSV into PostgreSQL.

Uses psycopg3's COPY protocol (`cursor.copy()`), not row-by-row INSERTs — a
100K-row dataset would take minutes via individual INSERT statements due to
per-statement round-trip and transaction overhead; COPY streams the whole
file to the server in one pass.

Only real `transactions` table columns are written. `is_fraud` (the
synthetic-data-only training label — see generate_synthetic_data.py's module
docstring) and `amount_bucket` (a Postgres `GENERATED ALWAYS AS (...) STORED`
column the database computes itself) are both excluded; inserting either
would be either meaningless or outright rejected.

Connects using the same POSTGRES_* env var names as
infrastructure/docker/.env.example, defaulting to that file's dev values so
this "just works" against `make infra-up` without extra setup.
"""

from __future__ import annotations

import argparse
import os
from decimal import Decimal
from pathlib import Path

import pandas as pd
import psycopg

_SCRIPT_DIR = Path(__file__).resolve().parent
_DEFAULT_CSV_PATH = _SCRIPT_DIR.parent / "data" / "synthetic_transactions.csv"

# Real `transactions` table columns, in COPY order. Deliberately excludes
# `is_fraud` (not a DB column) and `amount_bucket` (DB-generated). `metadata`
# is also omitted — the generator never populates it, and it's nullable, so
# leaving it out of the COPY column list is equivalent to writing NULL.
_COPY_COLUMNS: tuple[str, ...] = (
    "id",
    "tenant_id",
    "customer_id",
    "amount",
    "currency",
    "merchant_name",
    "merchant_category",
    "merchant_id",
    "location_lat",
    "location_lng",
    "country_code",
    "is_online",
    "is_foreign",
    "channel",
    "device_id",
    "ip_address",
    "created_at",
    "updated_at",
)


def _connection_string() -> str:
    """Reads POSTGRES_HOST/PORT/DB/USER/PASSWORD from the environment.

    POSTGRES_HOST isn't in .env.example (Docker Compose gives services the
    `postgres` hostname internally; this script runs on the host, outside
    Compose's network), so it defaults to localhost — matching how
    docker-compose.infra.yml publishes Postgres to 127.0.0.1 for `make
    infra-up`. Other defaults mirror .env.example's dev values exactly.
    """
    host = os.environ.get("POSTGRES_HOST", "localhost")
    port = os.environ.get("POSTGRES_PORT", "5432")
    database = os.environ.get("POSTGRES_DB", "lynceus")
    user = os.environ.get("POSTGRES_USER", "lynceus")
    password = os.environ.get("POSTGRES_PASSWORD", "lynceus_dev_password")
    return f"host={host} port={port} dbname={database} user={user} password={password}"


def seed_from_csv(csv_path: Path, *, connection_string: str | None = None) -> int:
    """Bulk-loads `csv_path` into the `transactions` table. Returns the row count."""
    dataframe = pd.read_csv(csv_path)

    # Last line of defense before real DB writes: don't trust the generator
    # blindly, even though it already sets this correctly.
    dataframe["tenant_id"] = "default"

    # Parse into native Python types so psycopg's adapters (not ad-hoc string
    # formatting) handle serialization for each column's Postgres type.
    # format="ISO8601" (not the default strict-format inference) tolerates a
    # mix of whole-second and fractional-second timestamps in the same
    # column, which pandas otherwise rejects outright.
    dataframe["created_at"] = pd.to_datetime(dataframe["created_at"], format="ISO8601")
    dataframe["updated_at"] = pd.to_datetime(dataframe["updated_at"], format="ISO8601")
    dataframe["amount"] = dataframe["amount"].apply(lambda value: Decimal(str(value)))
    dataframe["location_lat"] = dataframe["location_lat"].apply(lambda value: Decimal(str(value)))
    dataframe["location_lng"] = dataframe["location_lng"].apply(lambda value: Decimal(str(value)))

    conn_str = connection_string or _connection_string()
    copy_sql = f"COPY transactions ({', '.join(_COPY_COLUMNS)}) FROM STDIN"
    with psycopg.connect(conn_str) as connection, connection.cursor() as cursor:
        with cursor.copy(copy_sql) as copy:
            for row in dataframe[list(_COPY_COLUMNS)].itertuples(index=False, name=None):
                copy.write_row(row)
        connection.commit()

    return len(dataframe)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--csv", type=Path, default=_DEFAULT_CSV_PATH, help="Synthetic transactions CSV to load"
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    print(f"Seeding {args.csv} into the transactions table ...")
    inserted = seed_from_csv(args.csv)
    print(f"Seeded {inserted} rows into transactions.")


if __name__ == "__main__":
    main()
