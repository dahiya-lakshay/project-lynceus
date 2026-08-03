# Phase 2: Event-Driven Architecture + Auth — Implementation Plan

> **Goal**: Everything Phase 1 does, but async (Kafka replaces the synchronous HTTP call from Transaction Service to Inference Service), authenticated (Keycloak-issued JWTs replace the `X-Tenant-Id` header stopgap), and with two new services (Alert, Customer Profile) plus a second model (Autoencoder, ensembled with Isolation Forest). The dashboard gains a live feed, an alert queue, and customer profile pages.
>
> **Builds on**: Phase 1 (`v0.1.0`) is complete and merged to `main`. This plan assumes that codebase as its starting point — read `docs/implementation_plan_phase-1.md` for what already exists before starting any task here; do not re-derive it.
>
> **Not in scope for Phase 2**: Apache Flink, the Redis feature store, SHAP explainability, human-in-the-loop feedback, graph fraud detection, the RAG chatbot, OpenTelemetry/Prometheus, Kubernetes, k6 load testing. Those are Phase 3+ (see `docs/BRD_specs.md` §13).

---

## Prerequisites

In addition to everything Phase 1 required:

| Tool | Version | Purpose |
|------|---------|---------|
| PyTorch | 2.x (CPU build is fine for Phase 2 dev) | Autoencoder model |
| `kcat` (formerly `kafkacat`) | latest | Manual Kafka topic inspection during development (optional but recommended) |
| MLflow | 2.x (run via Docker Compose, no local install needed) | Experiment tracking + model registry |

No new local installs are strictly required beyond what Phase 1 already needed — Kafka, Keycloak, and MLflow all run as Docker Compose services.

---

## Architecture Change Summary

Before diving into tasks, the shape of what's changing, since it cuts across almost every existing service:

- **Synchronous → event-driven scoring**: `TransactionService.create()` currently calls `InferenceClient.score()` synchronously in the request path (a blocking HTTP call the Phase 1 review already flagged once for holding a DB connection open too long, since fixed by narrowing the transaction boundary — Phase 2 removes the HTTP call entirely). Instead: Transaction Service publishes a `transaction.created` event to Kafka and returns `202` immediately with no score. Inference Service consumes `transaction.created`, scores it, and publishes `fraud.scored`. Transaction Service (or the BFF directly, per the architecture diagram) consumes `fraud.scored` to update the transaction's cached view. This is a real behavior change for API consumers: `GET /api/v1/transactions/{id}` immediately after a `POST` will now normally show `risk_level: null` until the async pipeline catches up — Phase 1's synchronous-scoring assumption is gone.
- **Stopgap tenant header → real JWT-based tenant**: `TenantFilter` (shared-lib, Phase 1) defaults to `"default"` when `X-Tenant-Id` is absent. Phase 2 replaces this with a Keycloak JWT that NGINX validates at the edge and forwards as validated claims; each service's tenant resolution reads the `tenant_id`/`realm` claim from the token instead of trusting a client-supplied header. `TenantContext`/`TenantFilter` themselves (the `ThreadLocal` + cleanup pattern) stay — only *how* they get populated changes.
- **Dashboard BFF: Spring MVC → WebFlux**: needed for the WebSocket/SSE live feed (`docs/BRD_specs.md` §11 explicitly calls this out — reactive is the natural fit for a server-push feed, and it's the only Phase 2 service that needs it; Transaction/Alert/Customer services stay on Spring MVC + virtual threads).
- **Two new Java services**: Alert Service, Customer Profile Service — same conventions as Transaction Service (Spring Boot 3, `shared-lib` dependency, Liquibase-owned tables, tenant-scoped queries).
- **`isolation_forest.joblib` on local disk → MLflow Model Registry**: Inference Service loads both models (Isolation Forest + Autoencoder) from MLflow's registry at startup instead of a git-ignored local file path.

---

## Task Sequence Overview

```mermaid
graph LR
    T1["Task 1<br/>Kafka Infra"] --> T6
    T2["Task 2<br/>Keycloak Infra"] --> T6
    T3["Task 3<br/>API Specs +<br/>Event Schemas"] --> T4
    T3 --> T5
    T4["Task 4<br/>DB Migrations<br/>(alerts, customers)"] --> T8
    T4 --> T9
    T5["Task 5<br/>Shared-lib:<br/>JWT + Kafka DTOs"] --> T6
    T5 --> T7
    T5 --> T8
    T5 --> T9
    T1 --> T7
    T3 --> T7
    T6["Task 6<br/>Transaction Service<br/>Event-Driven + Auth"] --> T11
    T7["Task 7<br/>Inference Service<br/>Event-Driven + Autoencoder"] --> T10
    T7 --> T11
    T1 --> T8
    T3 --> T8
    T8["Task 8<br/>Alert Service (New)"] --> T11
    T2 --> T9
    T3 --> T9
    T9["Task 9<br/>Customer Service (New)"] --> T11
    T10["Task 10<br/>MLflow Integration"] --> T14
    T11["Task 11<br/>Dashboard BFF<br/>WebFlux + WebSocket"] --> T12
    T2 --> T12
    T8 --> T12
    T9 --> T12
    T12["Task 12<br/>Frontend: Auth,<br/>Alerts, Customers,<br/>Live Feed"] --> T13
    T13["Task 13<br/>CI Pipeline<br/>Updates"] --> T14
    T14["Task 14<br/>Integration &<br/>E2E Verification"]
```

> [!IMPORTANT]
> Same conventions as Phase 1: each task = one feature branch off `develop`, one isolated review (independent code review + `security-review` skill) before merge, `--no-ff` merge, no GitHub PRs. Tasks 1–5 have no inter-dependencies and can run fully in parallel (five-way). Tasks 6–9 depend on subsets of 1–5 but not on each other — run them in parallel once their dependencies land. Task 10 only needs Task 7. Task 11 needs 6, 7, 8, 9. Task 12 needs 2, 8, 9, 11. Tasks 13–14 are sequential and final, same as Phase 1's Task 11–12.

---

## Task 1: Kafka Infrastructure

**Branch**: `feature/kafka-infra`
**Depends on**: Nothing (Phase 1 infra already merged)

### What We're Doing

A single-broker Kafka cluster in KRaft mode (no ZooKeeper — Kafka 3.5+ supports KRaft as the default, simpler for a single-broker dev setup and what any new Kafka deployment should use in 2026), topics created via an init container, and Spring Kafka / `aiokafka` wiring added to both services that will produce/consume in later tasks (this task only stands up the broker + topics; producer/consumer code lands in Tasks 6–8).

### Files to Create

#### 1.1 `infrastructure/docker/docker-compose.infra.yml` (extend)

Add a `kafka` service:

```yaml
kafka:
  image: apache/kafka:3.8.0
  container_name: lynceus-kafka
  restart: unless-stopped
  environment:
    KAFKA_NODE_ID: 1
    KAFKA_PROCESS_ROLES: broker,controller
    KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093,PLAINTEXT_HOST://0.0.0.0:9094
    KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,PLAINTEXT_HOST://localhost:${KAFKA_HOST_PORT:-9094}
    KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
    KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
    KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
    KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
    KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
    KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
    KAFKA_NUM_PARTITIONS: 3
    CLUSTER_ID: "lynceus-dev-cluster-1"
  ports:
    - "127.0.0.1:${KAFKA_HOST_PORT:-9094}:9094"
  volumes:
    - lynceus-kafkadata:/var/lib/kafka/data
  healthcheck:
    test: ["CMD-SHELL", "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 || exit 1"]
    interval: 10s
    timeout: 10s
    retries: 10
    start_period: 20s
  networks:
    - lynceus-network

kafka-init:
  image: apache/kafka:3.8.0
  container_name: lynceus-kafka-init
  depends_on:
    kafka:
      condition: service_healthy
  entrypoint: ["/bin/bash", "-c"]
  command: >
    "
    /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --if-not-exists --topic transaction.created --partitions 3 --replication-factor 1 --config retention.ms=604800000 &&
    /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --if-not-exists --topic fraud.scored --partitions 3 --replication-factor 1 --config retention.ms=604800000 &&
    /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --if-not-exists --topic fraud.scored.dlq --partitions 1 --replication-factor 1 --config retention.ms=2592000000 &&
    echo 'Topics created.'
    "
  networks:
    - lynceus-network
```

Add `lynceus-kafkadata` to the top-level `volumes:` block. Bind the host port to `127.0.0.1` only, matching every other infra port in this project (Task 2's Phase 1 review fixed this exact pattern for Postgres/Redis — don't regress it here).

**Topic design**:

| Topic | Producer | Consumer(s) | Key | Partitions |
|-------|----------|-------------|-----|------------|
| `transaction.created` | Transaction Service | Inference Service | `tenant_id:transaction_id` | 3 |
| `fraud.scored` | Inference Service | Transaction Service, Alert Service, Dashboard BFF | `tenant_id:transaction_id` | 3 |
| `fraud.scored.dlq` | Inference Service (on unrecoverable scoring failure) | (manual inspection / future reprocessing job) | same as `fraud.scored` | 1 |

Keying by `tenant_id:transaction_id` guarantees all events for one transaction land on the same partition, preserving per-transaction ordering without needing a single-partition topic.

#### 1.2 `infrastructure/docker/.env.example` (extend)
```env
# Kafka
KAFKA_HOST_PORT=9094
KAFKA_BOOTSTRAP_SERVERS=kafka:9092
```

#### 1.3 `services/gradle/libs.versions.toml` (extend)
Add `spring-kafka` to the version catalog (version matching the Spring Boot 3.4.x BOM already in use — let dependency management resolve the exact patch version rather than hardcoding one that might drift from the BOM).

#### 1.4 `ml-services/inference-service/pyproject.toml` (extend)
Add `aiokafka>=0.11.0` (async-native, fits the existing `asyncio` architecture — don't use `kafka-python`, which is sync-only and would need a thread-pool wrapper for no benefit).

### Commits
```bash
git checkout develop && git checkout -b feature/kafka-infra

git add infrastructure/docker/docker-compose.infra.yml infrastructure/docker/.env.example
git commit -m "chore(infra): add single-broker Kafka (KRaft) with topic init"

git add services/gradle/libs.versions.toml
git commit -m "build(shared): add spring-kafka to the version catalog"

git add ml-services/inference-service/pyproject.toml
git commit -m "build(inference-svc): add aiokafka dependency"
```

### Verification
```bash
make infra-up   # extend the Makefile target's compose file list if needed, or add a kafka-specific one
docker exec lynceus-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
# Should show: transaction.created, fraud.scored, fraud.scored.dlq
```

---

## Task 2: Keycloak Infrastructure

**Branch**: `feature/keycloak-infra`
**Depends on**: Nothing

### What We're Doing

Keycloak as a Docker Compose service, with a realm export checked into the repo so `make infra-up` gives every developer an identical local IAM setup — one realm (`lynceus-dev`) representing a single demo tenant/bank, with three roles and two seed users, matching `docs/BRD_specs.md` §5's RBAC model. Multiple realms (one per real tenant) are a Task 12/Phase-2-hardening concern once the single-realm flow works end to end — don't try to build realm-per-tenant provisioning automation in this task.

### Files to Create

#### 2.1 `infrastructure/docker/docker-compose.infra.yml` (extend)
```yaml
keycloak:
  image: quay.io/keycloak/keycloak:26.0
  container_name: lynceus-keycloak
  restart: unless-stopped
  command: ["start-dev", "--import-realm"]
  environment:
    KEYCLOAK_ADMIN: admin
    KEYCLOAK_ADMIN_PASSWORD: ${KEYCLOAK_ADMIN_PASSWORD}
    KC_HEALTH_ENABLED: "true"
  ports:
    - "127.0.0.1:${KEYCLOAK_PORT:-8090}:8080"
  volumes:
    - ./keycloak/realm-export.json:/opt/keycloak/data/import/realm-export.json:ro
  healthcheck:
    test: ["CMD-SHELL", "exec 3<>/dev/tcp/127.0.0.1/8080 && echo -e 'GET /health/ready HTTP/1.1\r\nhost: localhost\r\nConnection: close\r\n\r\n' >&3 && cat <&3 | grep -q '\"status\": \"UP\"'"]
    interval: 10s
    timeout: 5s
    retries: 15
    start_period: 30s
  networks:
    - lynceus-network
```
`start-dev` (not `start`) is deliberate for local dev — production Keycloak deployment (with a real DB backend, not the embedded dev one) is a Phase 5 hardening concern, out of scope here.

#### 2.2 `infrastructure/keycloak/realm-export.json`
A Keycloak realm export (JSON) defining:
- Realm `lynceus-dev`.
- Client `lynceus-dashboard` (public client, PKCE-enabled, redirect URIs covering `http://localhost:3000/*` and `http://localhost:8080/*`).
- Client `lynceus-services` (confidential/bearer-only client, or just rely on the dashboard client's tokens being validated by resource servers — use your judgment on the simplest correct setup for a single-realm dev environment; a full client-credentials service-to-service flow isn't needed yet since inter-service calls in Phase 2 go through Kafka, not direct HTTP with fresh tokens).
- Realm roles: `admin`, `fraud_analyst`, `viewer` (per §5's table; `ml_engineer` is mentioned in the BRD but has no Phase 2 feature that uses it yet — add the role for forward-compatibility, don't build anything gated on it this phase).
- A custom protocol mapper adding a `tenant_id` claim to issued tokens (hardcoded to `"default"` for this single-realm setup, so every service reading `tenant_id` from the JWT gets the same value Phase 1's stopgap used — this keeps the ~100K rows already seeded under `tenant_id='default'` valid and queryable without a data migration).
- Two seed users: `analyst@lynceus.dev` (role `fraud_analyst`) and `admin@lynceus.dev` (role `admin`), both with a known dev password set via the export (document it in the realm file's own comments/README, not just implicitly — this is dev-only, never production credentials).

Generate this by actually running Keycloak locally, configuring it through the admin console, and exporting — don't hand-write the realm JSON from scratch (Keycloak's export format has a lot of structural detail that's easy to get subtly wrong by hand, and a hand-written file risks not actually importing cleanly).

#### 2.3 `infrastructure/docker/.env.example` (extend)
```env
# Keycloak
KEYCLOAK_PORT=8090
KEYCLOAK_ADMIN_PASSWORD=lynceus_dev_admin_password
KEYCLOAK_ISSUER_URI=http://localhost:8090/realms/lynceus-dev
```

#### 2.4 `infrastructure/nginx/nginx.conf` (extend)
Add JWT validation at the gateway using `nginx` with the `njs` module or `auth_request` against a lightweight introspection endpoint — **or**, more simply and more consistently with how Spring Boot / FastAPI actually validate JWTs (signature + claims, not just presence), push validation down into each service via Spring Security's OAuth2 Resource Server support / FastAPI middleware instead of NGINX. Decide and document which layer is authoritative: per §5's sequence diagram, NGINX validates the JWT signature against Keycloak's public key and forwards validated claims, with each service trusting NGINX's forwarded headers rather than re-validating. This means NGINX needs the `nginx-oidc`/`lua-resty-openidc` module or equivalent — **if that turns out to be too heavy for this task's scope, the pragmatic Phase 2 fallback is: each service validates the JWT itself via Spring Security Resource Server (Java) / `python-jose` (Python) against Keycloak's JWKS endpoint, and NGINX just proxies without inspecting the token.** Choose the pragmatic fallback unless you have a concrete, tested reason the NGINX-layer approach is worth the added complexity this phase — document whichever you pick and why in this task's commit message and report.

### Commits
```bash
git checkout develop && git checkout -b feature/keycloak-infra

git add infrastructure/docker/docker-compose.infra.yml infrastructure/docker/.env.example
git commit -m "chore(infra): add Keycloak service"

git add infrastructure/keycloak/realm-export.json
git commit -m "chore(infra): add lynceus-dev realm export (roles, seed users, tenant_id claim mapper)"

git add infrastructure/nginx/nginx.conf   # only if the NGINX-layer approach was chosen
git commit -m "feat(infra): validate JWTs at the NGINX gateway"   # adjust message if the fallback was used instead
```

### Verification
```bash
make infra-up
curl http://localhost:8090/realms/lynceus-dev/.well-known/openid-configuration
# Should return the realm's OIDC discovery document

# Get a token for the seed analyst user (Resource Owner Password flow — dev/testing only, never use this flow in production):
curl -X POST http://localhost:8090/realms/lynceus-dev/protocol/openid-connect/token \
  -d "client_id=lynceus-dashboard" -d "grant_type=password" \
  -d "username=analyst@lynceus.dev" -d "password=<dev password>"
# Decode the returned access_token at jwt.io (or `jq`) and confirm it has realm_access.roles
# including "fraud_analyst" and a custom "tenant_id": "default" claim.
```

---

## Task 3: API Specifications for Phase 2

**Branch**: `feature/api-specifications-phase2`
**Depends on**: Nothing (extends the Phase 1 specs, already merged)

### What We're Doing

Same API-first discipline as Phase 1: define every new contract before writing service code. Three things need specs: the Alert Service's REST API, the Customer Service's REST API, and — since Kafka messages are a contract too, just not an HTTP one — an `api-specs/shared/schemas/events.yaml` documenting the `transaction.created` and `fraud.scored` event payloads (JSON Schema, referenced by both the Java producer/consumer code and the Python producer/consumer code as their shared source of truth, the same role `api-specs/shared/schemas/*.yaml` already plays for REST DTOs). Also extend `transaction-api.yaml`, `dashboard-bff-api.yaml`, and `errors.yaml` for what's changed.

### Files to Create

#### 3.1 `api-specs/shared/schemas/events.yaml`
- **`TransactionCreatedEvent`** — `event_id` (uuid), `event_type` (const `"transaction.created"`), `tenant_id`, `transaction_id`, `customer_id`, `amount`, `merchant_category`, `is_online`, `is_foreign`, `channel`, `transaction_timestamp`, `occurred_at` (event envelope timestamp, distinct from the business timestamp).
- **`FraudScoredEvent`** — `event_id`, `event_type` (const `"fraud.scored"`), `tenant_id`, `transaction_id`, `isolation_forest_score`, `autoencoder_score`, `ensemble_score`, `risk_level`, `model_version` (now referencing an MLflow run/version identifier, not a hand-set string), `feature_vector`, `scored_at`, `occurred_at`.
- A shared **`EventEnvelope`** base shape (`event_id`, `event_type`, `occurred_at`) both events extend, so any future event type follows the same envelope convention.

#### 3.2 `api-specs/alert-api.yaml`
**Alert Service API**, tag `Alerts`, server `http://localhost:8084` (next free port after the BFF's 8083).

| Method | Path | Description | Response |
|--------|------|-------------|----------|
| `GET` | `/api/v1/alerts` | Paginated alert queue, filterable by `status`, `severity`, `assigned_to`, date range | `200` → paginated `Alert[]` |
| `GET` | `/api/v1/alerts/{id}` | Single alert with full transaction/score context | `200` → `Alert` |
| `PATCH` | `/api/v1/alerts/{id}` | Update status/assignment/resolution notes (analyst workflow) | `200` → updated `Alert` |
| `POST` | `/api/v1/alerts/{id}/escalate` | Escalate severity, reset SLA timer | `200` → updated `Alert` |

Schema `Alert`: mirrors the `alerts` table already sketched in `docs/BRD_specs.md` §8 (`id`, `tenant_id`, `transaction_id`, `fraud_score_id`, `severity`, `status` enum `open|investigating|confirmed_fraud|false_positive|closed`, `assigned_to`, `resolution_notes`, `created_at`, `updated_at`, `resolved_at`, `sla_deadline`). Requires `X-Tenant-Id` in Phase 2's transitional period the same way Phase 1's services did, **but** every endpoint also declares Bearer JWT security now (not the Phase 1 stub) since Alert Service is new and should be built auth-aware from the start rather than inheriting a stopgap.

#### 3.3 `api-specs/customer-api.yaml`
**Customer Profile Service API**, tag `Customers`, server `http://localhost:8085`.

| Method | Path | Description | Response |
|--------|------|-------------|----------|
| `GET` | `/api/v1/customers/{id}` | Customer risk profile | `200` → `CustomerProfile` |
| `GET` | `/api/v1/customers` | Paginated/searchable customer list | `200` → paginated `CustomerProfile[]` |
| `GET` | `/api/v1/customers/{id}/transactions` | Customer's transaction history timeline | `200` → paginated `TransactionSummary[]` (proxied from/joined with Transaction Service data — decide in Task 9 whether this service calls Transaction Service's API or reads a denormalized projection; document the choice there, not here) |

Schema `CustomerProfile`: mirrors the `customers` table in §8 (`id`, `tenant_id`, `external_id`, `name`, `email`, `phone`, `risk_score`, `risk_level`, `avg_transaction_amount`, `total_transactions`, `total_fraud_alerts`, `home_location_lat/lng`, `profile_metadata`, `created_at`, `updated_at`).

#### 3.4 `api-specs/transaction-api.yaml` (extend)
- Update `POST /api/v1/transactions`'s response description: `risk_level`/`fraud_score` are now **always** null on the initial response (async scoring, no exceptions) — remove any wording implying synchronous best-effort scoring.
- Add a note (description field, not a new endpoint) that clients wanting score updates should poll `GET /api/v1/transactions/{id}` or use the dashboard's live feed (BFF WebSocket) — Phase 2 doesn't add a webhook/callback mechanism.

#### 3.5 `api-specs/dashboard-bff-api.yaml` (extend)
Add a WebSocket endpoint description (OpenAPI 3.1 doesn't natively model WebSocket contracts well — document it as a prose section in the file's top-level `description`, or use AsyncAPI for this one contract if you judge it worth introducing a second spec format; simplest is a clearly-written prose block, since this is the only async endpoint in the whole system so far and doesn't justify a new tool). Message shape: server pushes `FraudScoredEvent`-shaped JSON (reuse the shared schema) to every connected client scoped to their tenant, on each `fraud.scored` Kafka event the BFF consumes.

### Commits
```bash
git checkout develop && git checkout -b feature/api-specifications-phase2

git add api-specs/shared/schemas/events.yaml
git commit -m "docs(api-spec): define Kafka event schemas for transaction.created and fraud.scored"

git add api-specs/alert-api.yaml
git commit -m "docs(api-spec): define Alert Service API contract"

git add api-specs/customer-api.yaml
git commit -m "docs(api-spec): define Customer Profile Service API contract"

git add api-specs/transaction-api.yaml
git commit -m "docs(api-spec): update transaction-api for async scoring semantics"

git add api-specs/dashboard-bff-api.yaml
git commit -m "docs(api-spec): document the BFF WebSocket live-feed contract"
```

### Verification
```bash
make api-validate   # extend to include alert-api.yaml, customer-api.yaml, events.yaml
```

---

## Task 4: Database Migrations (Alerts, Customers, RLS)

**Branch**: `feature/database-migrations-phase2`
**Depends on**: Task 3 (schema should reflect the finalized API contracts)

### What We're Doing

`alerts` and `customers` tables (per §8, already reasoned about in Phase 1's Task 3 for `transactions`/`fraud_scores`), plus the first real PostgreSQL Row-Level Security policies — Phase 1 deferred RLS as a Phase-2 concern (see the Task 3 review's finding #4 from Phase 1: "composite FK tying fraud_scores.tenant_id to its parent transaction's tenant... not a Phase-1 blocker... worth a Phase-2 follow-up alongside RLS"). This task is that follow-up.

### Files to Create

#### 4.1 `infrastructure/db/migrations/changelogs/YYYYMMDD-05-create-customers-table.yaml`
(Use the actual date this task is executed for the filename prefix, continuing Phase 1's `YYYYMMDD-NN-description.yaml` convention and numbering from `-04-` where Phase 1 left off — check `infrastructure/db/migrations/db.changelog-master.yaml` for the current highest number before picking the next one.)

`customers` table per §8's schema, with the same Phase-1 rigor applied: every column the Customer API's OpenAPI schema marks `required` gets `NOT NULL` in the DB (don't repeat Phase 1's first-draft mistake of leaving the DB weaker than the contract — check this proactively this time), `created_at`/`updated_at` TIMESTAMPTZ NOT NULL, `UNIQUE(tenant_id, external_id)`.

#### 4.2 `infrastructure/db/migrations/changelogs/YYYYMMDD-06-create-alerts-table.yaml`
`alerts` table per §8. `transaction_id` and `fraud_score_id` — decide whether these are FKs (they should be, for the same referential-integrity reasons Phase 1's `fraud_scores.transaction_id` FK exists) with `NO ACTION` on delete (same reasoning as Phase 1: an alert must never silently vanish because its underlying transaction was deleted — document this choice with a comment, matching Phase 1's established pattern). `sla_deadline` — computed application-side from `severity` at alert-creation time (Alert Service's job, Task 8), not a DB-generated column.

#### 4.3 `infrastructure/db/migrations/changelogs/YYYYMMDD-07-enable-row-level-security.yaml`
Enable RLS on `transactions`, `fraud_scores`, `customers`, `alerts`:
```sql
ALTER TABLE transactions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_transactions ON transactions
  USING (tenant_id = current_setting('app.current_tenant_id', true));
-- Repeat for fraud_scores, customers, alerts
```
Each service's datasource needs to `SET app.current_tenant_id = '<tenant>'` per-connection/per-request (a Spring `@Transactional`-friendly place is a Hibernate `StatementInspector` or a `DataSource` proxy that runs `SET` right after checkout — research the cleanest Spring Boot 3 pattern for this rather than guessing; this is genuinely the trickiest part of this task). Document clearly: RLS is a **defense-in-depth safety net** per AGENTS.md's Security section ("PostgreSQL RLS as a safety net") — application-level `tenant_id` filtering (already correct everywhere per Phase 1's reviews) remains the primary mechanism, RLS catches the case where a future query forgets it.

### Commits
```bash
git checkout develop && git checkout -b feature/database-migrations-phase2

git add infrastructure/db/migrations/changelogs/*customers*
git commit -m "chore(infra): add customers table migration"

git add infrastructure/db/migrations/changelogs/*alerts*
git commit -m "chore(infra): add alerts table migration"

git add infrastructure/db/migrations/changelogs/*row-level-security*
git commit -m "chore(infra): enable PostgreSQL RLS as a multi-tenancy safety net"
```

### Verification
Same pattern as Phase 1 Task 3: bring up Postgres via `docker-compose.infra.yml`, run the Liquibase container against it, confirm tables/indexes/FKs exist, **and additionally**: connect as a non-superuser role, `SET app.current_tenant_id = 'tenant-a'`, insert a row for `tenant-a` and one for `tenant-b` directly (bypassing the app), confirm a `SELECT` only returns `tenant-a`'s row. This is a real security control — verify it actually works, don't just check the migration applied without error.

---

## Task 5: Shared-lib Updates — JWT Security + Kafka Event DTOs

**Branch**: `feature/shared-lib-phase2`
**Depends on**: Task 3 (event schemas, Alert/Customer API contracts)

### What We're Doing

Extends `shared-lib` (Phase 1) with what every Phase 2 Java service needs: JWT-based tenant resolution (replacing the header stopgap), and Kafka event DTOs matching `events.yaml`.

### Files to Create

#### 5.1 `services/shared-lib/src/main/java/com/lynceus/shared/config/JwtTenantFilter.java`
Replaces (or sits alongside, transitionally — decide and document) Phase 1's `TenantFilter`. Depending on Task 2's NGINX-vs-service-level JWT validation decision:
- If NGINX validates and forwards claims as headers: this filter reads the forwarded header(s) instead of raw `Authorization`, still populating `TenantContext` the same way.
- If each service validates independently: use Spring Security's OAuth2 Resource Server support (`spring-boot-starter-oauth2-resource-server`, JWKS-based, pointed at Keycloak's `KEYCLOAK_ISSUER_URI`) and extract `tenant_id`/`realm_access.roles` from the validated `Jwt` principal.

Either way: **no more silent `"default"` fallback when auth is absent** — a missing/invalid token is now a real `401`, not a stopgap default. (The already-seeded `tenant_id='default'` data stays valid because Task 2's realm mapper issues `tenant_id: "default"` in every token for the single dev realm — so existing data remains reachable without a data migration, only the *mechanism* for getting to `"default"` changes from "absent header" to "validated token claim.")

#### 5.2 `services/shared-lib/src/main/java/com/lynceus/shared/security/Roles.java`
Constants/enum for `admin`, `fraud_analyst`, `viewer`, `ml_engineer` matching the realm's roles, for use in `@PreAuthorize`/`@Secured` annotations in Tasks 6/8/9 — define once here so role name strings aren't duplicated/mistyped across three services.

#### 5.3 `services/shared-lib/src/main/java/com/lynceus/shared/event/TransactionCreatedEvent.java`, `FraudScoredEvent.java`
Java records matching `events.yaml` exactly (field-for-field, same snake_case-via-`@JsonProperty` discipline Phase 1 established for REST DTOs — Kafka payloads are JSON too, same rules apply).

#### 5.4 `services/shared-lib/build.gradle.kts` (extend)
Add `spring-kafka` (for `KafkaTemplate`/`@KafkaListener` type references shared DTOs might need) and `spring-boot-starter-oauth2-resource-server` (if the service-level JWT validation path was chosen in Task 2).

### Commits
```bash
git checkout develop && git checkout -b feature/shared-lib-phase2

git add services/shared-lib/src/main/java/com/lynceus/shared/config/ services/shared-lib/src/main/java/com/lynceus/shared/security/
git commit -m "feat(shared): replace tenant-header stopgap with JWT-based tenant/role resolution"

git add services/shared-lib/src/main/java/com/lynceus/shared/event/
git commit -m "feat(shared): add Kafka event DTOs matching the events.yaml contract"

git add services/shared-lib/build.gradle.kts
git commit -m "build(shared): add spring-kafka and OAuth2 resource server dependencies"
```
Add unit tests for the new filter/JWT-claim-extraction logic, same rigor as Phase 1's `TenantContextTest`/`TenantFilterTest`.

### Verification
```bash
cd services && ./gradlew :shared-lib:test
```

---

## Task 6: Transaction Service — Event-Driven Refactor + Auth

**Branch**: `feature/transaction-service-phase2`
**Depends on**: Task 1 (Kafka), Task 2 (Keycloak), Task 5 (shared-lib)

### What We're Doing

Remove the synchronous `InferenceClient` HTTP call entirely. `TransactionService.create()` now: persists the transaction, publishes a `TransactionCreatedEvent` to `transaction.created`, and returns immediately (score fields null). A new `@KafkaListener` consumes `fraud.scored`, looks up the transaction, persists the `FraudScore`, and invalidates/updates the Redis cache entry Phase 1 already established. Also wires in the new JWT-based auth from Task 5, replacing `WebConfig`'s Phase 1 `TenantFilter` registration.

### Key Implementation Details

- **`TransactionEventProducer`** (new, `service/` package) — wraps `KafkaTemplate<String, TransactionCreatedEvent>`, keys messages `tenant_id:transaction_id` (matching Task 1's topic design), and — critically — publishes **after** the DB transaction commits, not before, using Spring's `TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` or an equivalent transactional-outbox-lite pattern. Publishing before commit risks a consumer processing an event for a transaction that a rolled-back DB write never actually persisted — a real correctness bug worth explicitly avoiding, not an edge case to skip.
- **`FraudScoredEventConsumer`** (new) — `@KafkaListener(topics = "fraud.scored")`, looks up the `Transaction` by `transaction_id` (tenant-scoped, same as every other Phase 1 query), persists a `FraudScore` row, invalidates the Redis cache key so the next `GET` reflects the new score (Phase 1's cache had a fixed TTL and no invalidation since nothing ever changed a cached transaction after creation — that assumption is now false, so this is a genuinely new caching concern, not a copy-paste of Phase 1's pattern). Handle the case where the transaction lookup fails (e.g., a bug elsewhere produced an orphan event) by logging at `ERROR` and **not** blindly retrying forever — configure Spring Kafka's error handling to send to `fraud.scored.dlq` after a bounded number of retries (`DefaultErrorHandler` with a `DeadLetterPublishingRecoverer`), not an infinite-retry consumer that could block the partition.
- **Remove** `InferenceClient.java` and `RestClientConfig`'s inference-service-specific bean (kept only if something else still needs to call another service directly — check before deleting wholesale).
- **`GET /api/v1/transactions/{id}`/list endpoints**: no functional change needed (they already correctly return null score fields gracefully — Phase 1's graceful-degradation path becomes Phase 2's *normal* path, not a fallback).
- **Auth**: apply `@PreAuthorize("hasRole('fraud_analyst') or hasRole('admin')")`-style checks (or the resource-server equivalent) on write endpoints; `viewer` role gets read-only. Use `Roles` constants from Task 5, not hardcoded strings.

### Commits
```bash
git checkout develop && git checkout -b feature/transaction-service-phase2

git add services/transaction-service/src/main/java/com/lynceus/transaction/service/TransactionEventProducer.java
git add services/transaction-service/src/main/java/com/lynceus/transaction/config/KafkaConfig.java
git commit -m "feat(txn-svc): publish transaction.created events after commit"

git add services/transaction-service/src/main/java/com/lynceus/transaction/service/FraudScoredEventConsumer.java
git commit -m "feat(txn-svc): consume fraud.scored events and invalidate the transaction cache"

# Removal as its own commit, not folded into an addition — makes the history easy to bisect
git rm services/transaction-service/src/main/java/com/lynceus/transaction/service/InferenceClient.java
git commit -m "refactor(txn-svc): remove synchronous InferenceClient, superseded by Kafka"

git add services/transaction-service/src/main/java/com/lynceus/transaction/config/WebConfig.java
git commit -m "feat(txn-svc): wire JWT-based auth in place of the tenant-header stopgap"

git add services/transaction-service/src/test/
git commit -m "test(txn-svc): add tests for event production/consumption and JWT auth"
```

### Verification
- Unit + Testcontainers integration tests (extend the existing Testcontainers setup with a Kafka container — `org.testcontainers:kafka`, already need to add this test dependency).
- Live: `make infra-up` (now includes Kafka/Keycloak), `bootRun` transaction-service, POST a transaction, confirm it returns `202` with null score fields immediately, `kcat -b localhost:9094 -t transaction.created -C` shows the published event.

---

## Task 7: ML Inference Service — Event-Driven + Autoencoder Ensemble

**Branch**: `feature/inference-service-phase2`
**Depends on**: Task 1 (Kafka), Task 3 (event schemas)

### What We're Doing

Add a Kafka consumer (`transaction.created` → score → publish `fraud.scored`) alongside the existing REST endpoints (keep `/api/v1/scoring/score`/`/batch` — useful for manual testing/tooling even once the primary path is event-driven; don't delete a working, tested capability without a reason). Add a PyTorch Autoencoder model, trained on the same synthetic data (Task 10's training-pipeline extension, not this task), and ensemble its reconstruction-error-based anomaly score with the existing Isolation Forest score.

### Key Implementation Details

- **`kafka_consumer.py`** (new, `api/` or a new `messaging/` package) — `aiokafka.AIOKafkaConsumer` on `transaction.created`, calls the same `ScoringService.score()` the REST path already uses (don't duplicate scoring logic between the HTTP and Kafka entry points — both should call one shared service method, exactly the principle Phase 1 established for feature-engineering reuse between the inference service and the training pipeline).
- **`kafka_producer.py`** — publishes `FraudScoredEvent` to `fraud.scored`. On a scoring failure (model error, malformed event), publish to `fraud.scored.dlq` instead of silently dropping the event or crashing the consumer loop — catch specific exceptions, not bare `Exception`, per AGENTS.md.
- **`autoencoder.py`** (new, `models/`) — a PyTorch `nn.Module` autoencoder over the same `FEATURE_COLUMNS` the Isolation Forest uses (reuse, don't duplicate, per Phase 1's established pattern). Reconstruction error (MSE between input and decoder output) is the anomaly signal — higher error, more anomalous. Needs its own normalization to `[0,1]` (same category of problem Phase 1's `IsolationForestModel.predict()` solved with a sigmoid — decide independently whether a sigmoid or min-max scaling fits the autoencoder's error distribution better, don't just copy Isolation Forest's exact formula/constant without checking it's appropriate for a different underlying distribution).
- **`ensemble.py`** or extend `scoring_service.py` — combines `isolation_forest_score` and `autoencoder_score` into `ensemble_score`. Simplest defensible approach: a weighted average with weights as a configurable setting (not hardcoded), defaulting to equal weight (0.5/0.5) until Task 10's MLflow experiment tracking gives you real comparative data to tune it — document that the default is a placeholder pending that data, not a tuned final answer.
- **`ScoreTransactionResponse`/`FraudScoredEvent`** now populate `autoencoder_score` (Phase 1 left this nullable specifically for this reason — check `services/shared-lib/.../dto/FraudScoreDto.java` and the API spec, both already have the field).

### Commits
```bash
git checkout develop && git checkout -b feature/inference-service-phase2

git add ml-services/inference-service/src/inference_service/models/autoencoder.py
git commit -m "feat(inference-svc): add PyTorch autoencoder model"

git add ml-services/inference-service/src/inference_service/services/scoring_service.py
git commit -m "feat(inference-svc): ensemble isolation forest and autoencoder scores"

git add ml-services/inference-service/src/inference_service/messaging/
git commit -m "feat(inference-svc): consume transaction.created and publish fraud.scored via Kafka"

git add ml-services/inference-service/pyproject.toml
git commit -m "build(inference-svc): add torch dependency"

git add ml-services/inference-service/tests/
git commit -m "test(inference-svc): add tests for autoencoder scoring and Kafka consumer/producer"
```

### Verification
- `pytest tests/ -v`, `ruff check .`, `mypy src/` — same bar as Phase 1.
- Live: publish a synthetic `transaction.created` event by hand (`kcat -P` or a small script), confirm a `fraud.scored` event appears with both `isolation_forest_score` and `autoencoder_score` populated.

---

## Task 8: Alert Service (New)

**Branch**: `feature/alert-service`
**Depends on**: Task 1, Task 3, Task 4, Task 5

### What We're Doing

A new Spring Boot service, structured identically to Transaction Service (Phase 1's established package layout: `config/`, `controller/`, `service/`, `repository/`, `model/entity/`, `model/mapper/`, `exception/`). Consumes `fraud.scored`, evaluates simple threshold-based rules (a rules *engine* in the Drools/business-rules-DSL sense is explicitly not needed yet — `risk_level in (high, critical)` → create an alert is a sufficient Phase 2 rule; a real rules engine is a reasonable Phase 3 idea if analysts need to author their own rules, don't build one preemptively), and exposes the `alert-api.yaml` REST contract for the analyst workflow (view queue, assign, resolve, escalate).

### Key Implementation Details

- **`services/alert-service/`** — new Gradle module (add to `settings.gradle.kts`, uncomment-style like Phase 1 did for `transaction-service`/`dashboard-bff`).
- **`FraudScoredEventConsumer`** — same DLQ/retry-bounded pattern as Task 6's consumer. On `risk_level in (high, critical)`, creates an `Alert` row: `severity` derived from `risk_level` (e.g. `high`→`medium` severity, `critical`→`high` severity — or a simpler 1:1 mapping; decide and document, since the BRD doesn't pin this down exactly), `status = 'open'`, `sla_deadline` computed from severity (e.g. `critical` → now + 1 hour, `high` → now + 4 hours, `medium` → now + 24 hours — placeholder SLA windows, flag as configurable/tunable rather than hardcoded business fact).
- **`AlertController`** — implements `alert-api.yaml`'s four endpoints. `PATCH` updates are the analyst workflow's core action — validate status transitions make sense (e.g. can't go from `closed` back to `open` without an explicit reopen concept; decide the valid state-transition graph and enforce it server-side, don't accept an arbitrary status string).
- **No Spring State Machine** (the BRD mentions it as a "why Spring Boot" justification) unless the state-transition logic actually gets complex enough to need it — for four states and a handful of valid transitions, a plain enum + a validation method is simpler and this project's own principle (don't add abstractions beyond what the task requires) argues against pulling in a new framework for this.

### Commits
```bash
git checkout develop && git checkout -b feature/alert-service

git add services/settings.gradle.kts services/alert-service/build.gradle.kts services/alert-service/src/main/resources/
git commit -m "build(alert-svc): scaffold Alert Service Gradle module"

git add services/alert-service/src/main/java/com/lynceus/alert/model/
git commit -m "feat(alert-svc): add Alert JPA entity"

git add services/alert-service/src/main/java/com/lynceus/alert/repository/
git commit -m "feat(alert-svc): add tenant-scoped alert repository"

git add services/alert-service/src/main/java/com/lynceus/alert/service/
git commit -m "feat(alert-svc): add fraud.scored consumer and rule evaluation"

git add services/alert-service/src/main/java/com/lynceus/alert/controller/
git commit -m "feat(alert-svc): add REST controller for the analyst alert-queue workflow"

git add services/alert-service/Dockerfile
git commit -m "build(alert-svc): add multi-stage Dockerfile"

git add services/alert-service/src/test/
git commit -m "test(alert-svc): add unit and integration tests"
```

### Verification
Same bar as Phase 1's Transaction Service task: `./gradlew :alert-service:test` (including a Testcontainers integration test with a real Kafka + Postgres), `spotlessCheck`, then a live smoke test — publish a `fraud.scored` event with `risk_level: critical`, confirm an alert appears via `GET /api/v1/alerts`.

---

## Task 9: Customer Profile Service (New)

**Branch**: `feature/customer-service`
**Depends on**: Task 2, Task 3, Task 4, Task 5

### What We're Doing

Another new Spring Boot service, same conventions. Maintains customer risk profiles and aggregated stats. Decide explicitly (and document in your commit/report) whether this service:
(a) consumes `fraud.scored`/`transaction.created` from Kafka to maintain its own aggregates incrementally (event-driven, consistent with the rest of Phase 2's architecture), or
(b) computes aggregates on-demand by calling Transaction Service's `GET /api/v1/transactions/stats/*` endpoints (already built in Phase 1's Task 8 extension) when a profile is requested.
(a) is more consistent with the event-driven direction of this whole phase and avoids a synchronous service-to-service call the rest of Phase 2 is deliberately moving away from — prefer it unless there's a concrete reason (b) is meaningfully simpler for what Phase 2 actually needs.

### Key Implementation Details

- **`services/customer-service/`** — new Gradle module, same layout convention.
- **`CustomerProfile` entity** — per §8's schema.
- If event-driven (option a): a consumer updates `avg_transaction_amount`, `total_transactions` incrementally on each `transaction.created`, and `total_fraud_alerts`/`risk_score`/`risk_level` on each `fraud.scored` (or `Alert`-creation event, if you want it reacting to confirmed alerts rather than raw scores — decide and document). Incremental average maintenance: `new_avg = old_avg + (new_value - old_avg) / new_count` (Welford-style running mean) rather than re-summing the whole history each time.
- **`CustomerController`** — implements `customer-api.yaml`.
- Auth: `viewer` role sufficient for `GET` endpoints (customer profiles are read-heavy, browsed by any analyst).

### Commits
Same pattern as Task 8 (scaffold → entity → repository → service/consumer → controller → Dockerfile → tests), scope `customer-svc`.

### Verification
Same bar as Task 8. Live: publish a few `transaction.created`/`fraud.scored` events for one `customer_id`, confirm `GET /api/v1/customers/{id}` shows updated aggregates.

---

## Task 10: MLflow Integration

**Branch**: `feature/mlflow-integration`
**Depends on**: Task 7 (Inference Service needs both models built to register them)

### What We're Doing

MLflow as a Docker Compose service (tracking server + a local filesystem or Postgres-backed backend store — Postgres is already running, reuse it with a separate `mlflow` database rather than standing up a second datastore). The training pipeline (`ml-services/training-pipeline/`, Phase 1) logs experiments and registers both the Isolation Forest and the new Autoencoder as versioned models. The Inference Service loads models from the registry (`models:/lynceus-isolation-forest/Production`-style URIs) instead of a local `.joblib` path.

### Files to Create

#### 10.1 `infrastructure/docker/docker-compose.infra.yml` (extend)
```yaml
mlflow:
  image: ghcr.io/mlflow/mlflow:v2.17.0
  container_name: lynceus-mlflow
  restart: unless-stopped
  command: >
    mlflow server
    --host 0.0.0.0
    --port 5000
    --backend-store-uri postgresql://${POSTGRES_USER}:${POSTGRES_PASSWORD}@postgres:5432/mlflow
    --default-artifact-root /mlartifacts
  environment:
    MLFLOW_S3_IGNORE_TLS: "true"
  ports:
    - "127.0.0.1:${MLFLOW_PORT:-5000}:5000"
  volumes:
    - lynceus-mlflow-artifacts:/mlartifacts
  depends_on:
    postgres:
      condition: service_healthy
  networks:
    - lynceus-network
```
Needs a one-time `CREATE DATABASE mlflow;` — add it to `infrastructure/docker/postgres/init.sql` (already runs on first container start, per Phase 1's Task 2).

#### 10.2 `ml-services/training-pipeline/scripts/train_isolation_forest.py` (extend) and a new `train_autoencoder.py`
Wrap training runs in `mlflow.start_run()`, log hyperparameters, the evaluation metrics already computed (precision/recall/F1/ROC-AUC, and Phase 1's per-pattern breakdown — keep that, it was a real, hard-won insight worth preserving as tracked metrics, not just console output), and log the model artifact via `mlflow.sklearn.log_model()` / `mlflow.pytorch.log_model()`, registering it to the Model Registry under a fixed name (`lynceus-isolation-forest`, `lynceus-autoencoder`) and promoting it to the `Production` stage (or use the newer MLflow alias system, `champion`, if targeting an MLflow version where stages are deprecated in favor of aliases — check the actual MLflow 2.17 docs for which is current rather than assuming).

#### 10.3 `ml-services/inference-service/src/inference_service/core/config.py` (extend)
Add `mlflow_tracking_uri`, `isolation_forest_model_uri`, `autoencoder_model_uri` settings. `main.py`'s startup lifespan now loads via `mlflow.pyfunc.load_model(uri)` (or the sklearn/pytorch-specific loader) instead of `joblib.load(local_path)` — keep a fallback to the local-path behavior behind a settings flag for local dev/testing without a full MLflow stack running, rather than making MLflow a hard requirement for every test run (the existing test suite's stub-model approach shouldn't need to start depending on a live MLflow server).

### Commits
```bash
git checkout develop && git checkout -b feature/mlflow-integration

git add infrastructure/docker/docker-compose.infra.yml infrastructure/docker/postgres/init.sql
git commit -m "chore(infra): add MLflow tracking server backed by Postgres"

git add ml-services/training-pipeline/scripts/train_isolation_forest.py ml-services/training-pipeline/scripts/train_autoencoder.py
git commit -m "feat(ml): log training runs and register models to MLflow"

git add ml-services/inference-service/src/inference_service/core/config.py ml-services/inference-service/src/inference_service/main.py
git commit -m "feat(inference-svc): load models from MLflow registry instead of local joblib path"
```

### Verification
```bash
make infra-up
cd ml-services/training-pipeline && python scripts/train_isolation_forest.py
# Open http://localhost:5000, confirm a run appears with logged metrics and a registered model version
```
Bring up the inference service, confirm `/api/v1/scoring/health` reports a real model version pulled from MLflow (not the local file path string Phase 1 used).

---

## Task 11: Dashboard BFF — WebFlux + WebSocket + Kafka Consumer

**Branch**: `feature/dashboard-bff-phase2`
**Depends on**: Task 6, Task 7, Task 8, Task 9

### What We're Doing

Migrate `dashboard-bff` from Spring MVC (blocking `RestClient`) to Spring WebFlux (`WebClient`, reactive controllers). This is the biggest single refactor in Phase 2 for this service — budget real time for it. Add a WebSocket endpoint that pushes `fraud.scored` events to connected clients, and extend `DashboardService`'s aggregation to pull in Alert and Customer data for the dashboard pages Task 12 adds.

### Key Implementation Details

- **`build.gradle.kts`**: swap `spring-boot-starter-web` → `spring-boot-starter-webflux`, add `spring-kafka` (for the consumer) and a WebSocket dependency (WebFlux has built-in reactive WebSocket support, no extra library needed).
- **`TransactionClient`/new `AlertClient`/`CustomerClient`**: rewritten on `WebClient` instead of `RestClient`, returning `Mono<T>`/`Flux<T>`. This changes error handling (reactive `onErrorResume`/`onStatus` instead of try/catch around a blocking call) — Phase 1's `TransactionClient` graceful-degradation design (503 rather than silently zeroed data, since a fraud dashboard silently showing zeros during an outage is actively misleading — that finding from Phase 1's review still applies) needs to be re-expressed reactively, not dropped because the plumbing changed.
- **`FraudScoredEventConsumer`** (BFF's own, per the architecture diagram's `KAFKA --> BFF` edge) — on each `fraud.scored` event, pushes it to every currently-connected WebSocket session scoped to that tenant. Use a `Sinks.Many` (project reactor) per-tenant or a single sink with tenant-based filtering downstream — decide based on expected connection counts (a handful of analysts per tenant in dev/demo scale, nothing exotic needed).
- **`DashboardWebSocketHandler`** — new, implements `WebSocketHandler`, registered at `/ws/dashboard` (or similar), validates the JWT on the WebSocket handshake (WebSocket auth is a real gotcha — browsers can't set custom headers on the initial handshake request, so the token typically goes as a query param or a subprotocol; pick the standard approach and document why).
- **Overview/fraud-distribution/risk-breakdown endpoints**: same Redis caching Phase 1 built, just reactively wrapped (`ReactiveRedisTemplate` instead of `RedisTemplate`).
- **New aggregation**: `DashboardService` gains calls to Alert Service (open alert count for the overview KPIs) and Customer Service (top-risk customers, if a dashboard widget for that is worth adding — check with Task 12 before over-building here; this task should expose what Task 12's pages actually need, not invent extra endpoints speculatively).

### Commits
```bash
git checkout develop && git checkout -b feature/dashboard-bff-phase2

git add services/dashboard-bff/build.gradle.kts
git commit -m "build(bff): migrate to Spring WebFlux"

git add services/dashboard-bff/src/main/java/com/lynceus/bff/service/TransactionClient.java services/dashboard-bff/src/main/java/com/lynceus/bff/service/AlertClient.java services/dashboard-bff/src/main/java/com/lynceus/bff/service/CustomerClient.java
git commit -m "refactor(bff): rewrite service clients on reactive WebClient"

git add services/dashboard-bff/src/main/java/com/lynceus/bff/websocket/
git commit -m "feat(bff): add WebSocket live feed for fraud.scored events"

git add services/dashboard-bff/src/main/java/com/lynceus/bff/service/DashboardService.java services/dashboard-bff/src/main/java/com/lynceus/bff/controller/DashboardController.java
git commit -m "feat(bff): extend dashboard aggregation with alert and customer data"

git add services/dashboard-bff/src/test/
git commit -m "test(bff): update tests for reactive clients and WebSocket handler"
```

### Verification
`./gradlew :dashboard-bff:test` (reactive tests use `StepVerifier`, not plain JUnit assertions — a real shift from Phase 1's test style, don't just port the old blocking-style tests mechanically). Live: connect a WebSocket client (`websocat` or a small script) to the live-feed endpoint, publish a `fraud.scored` event, confirm it arrives over the socket in real time.

---

## Task 12: Frontend — Auth, Alerts, Customers, Live Feed

**Branch**: `feature/frontend-phase2`
**Depends on**: Task 2, Task 8, Task 9, Task 11

### What We're Doing

Keycloak login (OIDC), an alert queue page, a customer profiles page, and a live-updating feed on the overview page via the BFF's new WebSocket endpoint. Matches `docs/BRD_specs.md` §7's page table and route-group structure (`(auth)`/`(public)` route groups).

### Key Implementation Details

- **Auth**: `next-auth` (Auth.js) with the Keycloak OIDC provider is the standard, well-supported choice for Next.js — evaluate it first before hand-rolling OIDC token exchange; only hand-roll if `next-auth`'s App Router support genuinely doesn't fit (check its current state for whatever Next.js version is actually in use, confirmed in Phase 1 as 16.2.12).
- **`middleware.ts`** — protects `(auth)` routes, redirects unauthenticated requests to Keycloak login, matching §7/§5's flow diagrams.
- **Removing the Phase 1 stopgap**: `lib/api.ts`'s hardcoded `TENANT_ID = "default"` and the manually-set `X-Tenant-Id` header go away — the tenant now comes from the session's JWT (read server-side from the auth session, not client-managed state).
- **`app/(auth)/alerts/page.tsx`** — alert queue table (severity badges, status, SLA countdown), matching `recent-transactions.tsx`'s established table/badge patterns from Phase 1 rather than inventing a new table component style.
- **`app/(auth)/customers/page.tsx`** — customer list + a detail view (risk profile card, transaction history timeline — reuse Phase 1's transaction table component for the timeline rather than building a new one).
- **Live feed**: a client component (`"use client"`, necessarily — WebSocket needs the browser) on the overview page, using the browser's native `WebSocket` API or a small reconnecting-websocket helper (don't pull in a heavy library for this if the native API plus a simple reconnect-on-close loop suffices). Falls back gracefully if the socket can't connect (the page should still show the Phase 1 KPI cards/charts from the regular REST fetch — the live feed is additive, not a replacement for the existing working data flow).
- **Filter dropdowns on `/transactions`**: Phase 1's review found the BFF didn't proxy `risk_level`/`merchant_category`/date-range filters, so the frontend deliberately didn't build dead UI for them. Now's the time to actually wire this — confirm the BFF's `transactions/recent` proxy (or `TransactionClient`) forwards these query params through to Transaction Service's already-existing filtered `search()` query (built in Phase 1), and *then* add the filter dropdowns. Don't add the UI before confirming the plumbing underneath it actually works end to end.

### Commits
Same granularity pattern as Phase 1's Task 9 (auth/middleware → alerts page → customers page → live feed → filter wiring), scope `frontend`.

### Verification
`pnpm lint`/`type-check`/`build`. Live: log in as the seed `analyst@lynceus.dev` user through the actual Keycloak login flow, confirm the dashboard loads with that identity, create a high-risk transaction and confirm it appears on the live feed without a page refresh, confirm the alert queue shows the resulting alert, confirm transaction filters actually filter.

---

## Task 13: CI Pipeline Updates

**Branch**: `feature/ci-pipeline-phase2`
**Depends on**: Task 6, Task 7, Task 8, Task 9, Task 11, Task 12

### What We're Doing

Extend `.github/workflows/ci.yml` (Phase 1) for the two new services, Kafka-dependent tests, and the reactive BFF's different test style. Same investigate-before-assuming discipline Phase 1's CI task used (it turned out Phase 1 didn't need a shared Postgres `services:` block at all, once actually checked against the real test suite — verify fresh for Phase 2 rather than assuming Kafka needs one either; Testcontainers-based tests, if that's what Tasks 6–9 used, likely don't need a CI-level `services:` block for Kafka any more than they did for Postgres).

### What to Update
- `java-lint-test` job: now covers `alert-service`, `customer-service` too (matrix or just let `./gradlew test` at the root run everything — check which is actually cleaner given the multi-module setup).
- `docker-build` matrix: add `alert-service`, `customer-service`.
- New consideration: does any test need a live Keycloak? If Tasks 6–9's tests mock/stub JWT validation (recommended — a live Keycloak in CI is heavy and slow to start), confirm that's actually how it was built before assuming CI needs a Keycloak service container.
- `python-lint-test`: confirm `torch` installs cleanly in CI (it's a large dependency — check whether the CPU-only wheel is what gets installed by default, since a GPU build would be both unnecessary and much larger/slower to download on a CI runner).

### Commits
One or more commits, same pattern as Phase 1's CI task — investigate, fix, iterate against a real `gh pr create` + `gh run watch` cycle, don't just guess.

### Verification
Same bar as Phase 1: a real GitHub Actions run, watched via `gh run watch`, iterated until green, verified again once merged to `develop` via the `push` trigger.

---

## Task 14: Integration & End-to-End Verification

**Branch**: `feature/phase2-integration`
**Depends on**: Everything above, merged to `develop`

### What We're Doing

Same spirit as Phase 1's Task 12: this is the first time the *entire* Phase 2 stack (9 containers now: postgres, redis, kafka, keycloak, mlflow, transaction-service, inference-service, alert-service, customer-service, dashboard-bff, frontend, nginx — recount and update the Docker Compose file's full service list) runs together. Expect and budget time for real integration bugs, exactly like Phase 1's Task 12 found several (container-networking hostname resolution, healthcheck tool availability, `localhost`-vs-`127.0.0.1` musl resolution order) that only surfaced once everything ran together.

### Verification Checklist
- [ ] `make dev-all` starts all 12 containers healthy
- [ ] Login via Keycloak actually works end-to-end through the browser
- [ ] `POST /api/v1/transactions` returns `202` with **null** score fields (this is now correct behavior, not a degradation)
- [ ] A `fraud.scored` event appears on Kafka within a reasonable time window, and `GET /api/v1/transactions/{id}` subsequently shows the real ensemble score
- [ ] A high/critical-risk transaction produces a real row in the Alert Service's queue
- [ ] The customer profile for that transaction's `customer_id` shows updated aggregates
- [ ] The dashboard's live feed shows the scored transaction without a manual refresh
- [ ] MLflow shows a real registered model version, and the inference service reports loading from it (not a local file)
- [ ] RLS is actually enforced (re-run Task 4's verification against the full stack, not just an isolated Postgres container)
- [ ] Every service's own test suite and `spotlessCheck`/`ruff check` still pass
- [ ] CI is green on the `develop` push trigger

### Final Steps
Update `README.md` with the new (much longer) quick-start reflecting Keycloak login, Kafka topics, and the async scoring behavior change. Add `docs/architecture/decisions/002-event-driven-scoring.md` and `docs/architecture/decisions/003-keycloak-multi-tenancy.md` (two more ADRs — the async refactor and the auth model are both decisions worth the same explicit documentation Phase 1's ADR-001 gave the Java/Python split). Merge `develop` → `main`, tag `v0.2.0`.

---

## Phase 2 Summary

### What This Phase Adds

| Component | Status |
|-----------|--------|
| Apache Kafka (KRaft, single broker) | To build |
| Async event-driven scoring (`transaction.created` → `fraud.scored`) | To build |
| Autoencoder model + ensemble scoring | To build |
| Keycloak authentication + JWT-based tenant/role resolution | To build |
| PostgreSQL Row-Level Security | To build |
| Alert Service (new) | To build |
| Customer Profile Service (new) | To build |
| MLflow model registry | To build |
| Dashboard BFF → WebFlux + WebSocket live feed | To build |
| Dashboard: alert queue, customer profiles, login, live feed, working transaction filters | To build |

### What's Still NOT Built (Phase 3+)

Apache Flink stream processing, Redis feature store, model A/B testing, human-in-the-loop feedback, SHAP explainability, graph-based fraud detection, RAG chatbot + pgvector, OpenTelemetry, Prometheus/Grafana, Kubernetes/Helm, k6 load testing, GitOps. See `docs/BRD_specs.md` §13 for Phases 3–5.
