# Lynceus

**Lynceus** is an enterprise-grade, event-driven, microservice-based financial transaction fraud detection platform. It uses multi-model ML inference (Isolation Forest + Autoencoder), real-time stream processing, and a multi-tenant dashboard to detect and manage fraudulent activity.

> Named for the mythical Argonaut famed for extraordinarily sharp sight — the platform's job is to see fraud that would otherwise slip through.

This is a production-oriented system, built in phases. **Phase 1 (current)** delivers the MVP: `POST` a transaction → it's scored by an Isolation Forest model → the result is visible on a dashboard, all running in Docker Compose. See [`docs/implementation_plan_phase-1.md`](./docs/implementation_plan_phase-1.md) for the detailed build plan and [`docs/BRD_specs.md`](./docs/BRD_specs.md) for the full target architecture.

---

## Tech Stack

| Layer | Technology | Language |
|-------|-----------|----------|
| Core Business Services | Spring Boot 3.x | Java 21 |
| ML Services | FastAPI | Python 3.12+ |
| Stream Processing | Apache Flink 2.x | Java 21 |
| Frontend | Next.js 16 + Turborepo | TypeScript |
| Event Bus | Apache Kafka | — |
| Auth / IAM | Keycloak | — |
| API Gateway | NGINX | — |
| Database | PostgreSQL 17 + pgvector | SQL |
| Cache / Feature Store | Redis | — |
| ML Registry | MLflow | — |
| Notifications | Novu | — |
| Build (Java) | Gradle (Kotlin DSL) | — |
| Build (Python) | pyproject.toml + uv | — |
| Build (Frontend) | Turborepo + pnpm | — |
| Migrations | Liquibase (up-only) | — |

Kafka, Flink, the Autoencoder model, Keycloak, multi-tenancy, and the RAG chatbot are **not** part of Phase 1 — see the implementation plan for the phase-by-phase rollout.

---

## Quick Start

Prerequisites: Docker + Docker Compose v2, Java 21 (Temurin), Python 3.12+ with `uv`, Node 22 LTS with `pnpm`. Have at least a few GB of free disk — building all six application images from scratch (Gradle, pnpm, uv) is disk-hungry the first time.

```bash
# One-time local config — both are git-ignored, checked-in templates exist
cp infrastructure/docker/.env.example infrastructure/docker/.env
cp infrastructure/docker/redis/redis.conf.example infrastructure/docker/redis/redis.conf

# Infrastructure only (PostgreSQL + pgvector, Redis)
make infra-up

# Everything (all services + NGINX + frontend), via Docker Compose
make dev-all
```

`make dev-all` builds and starts, in dependency order: `postgres` and `redis` first, then `transaction-service` / `inference-service` / `dashboard-bff` (each waits on its own DB/cache dependencies via Compose healthchecks), then `frontend` (waits on `dashboard-bff`), then `nginx` last (waits on all four application services — NGINX resolves its upstreams once at startup, so every upstream must already be healthy or NGINX exits immediately). A cold first build of all six images typically takes 15-20 minutes, dominated by Gradle resolving dependencies fresh; subsequent builds are much faster via Docker layer caching.

Once everything is healthy, open **`http://localhost:8080`** for the dashboard (routed through NGINX) — this is the only port you should need for normal use.

### Verifying it end-to-end

```bash
# Gateway health
curl http://localhost:8080/health

# List transactions (paginated)
curl http://localhost:8080/api/v1/transactions -H "X-Tenant-Id: default"

# The core promise: POST a transaction, get back a real ML fraud score
curl -X POST http://localhost:8080/api/v1/transactions \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: default" \
  -d '{
    "customer_id": "550e8400-e29b-41d4-a716-446655440000",
    "amount": 15000.00,
    "merchant_name": "Suspicious Electronics Store",
    "merchant_category": "electronics",
    "is_online": true,
    "is_foreign": true,
    "channel": "online"
  }'
# -> 202 Accepted, with a non-null "fraud_score" and "risk_level" in the body
# (a large, foreign, online electronics purchase should score as high/critical risk)

# Dashboard KPIs
curl http://localhost:8080/api/v1/dashboard/overview -H "X-Tenant-Id: default"
```

This has been run end-to-end against a real trained Isolation Forest model and a Postgres volume seeded with ~100K synthetic transactions: the POST above returns a real fraud score (not null/degraded), and `http://localhost:8080/overview` and `/transactions` render actual KPI cards, charts, and a risk-level-badged data table with that seeded data through NGINX — not placeholder pages. Note that `/api/v1/dashboard/overview` is cached in Redis for 30s (cache-aside, no write-invalidation), so a GET immediately after a POST can briefly show pre-insert numbers; it reflects the new data once the TTL expires.

See [`AGENTS.md`](./AGENTS.md) and [`CLAUDE.md`](./CLAUDE.md) for the full set of build, test, lint, and database commands per service, [`api-specs/`](./api-specs/) for the OpenAPI contracts each service implements, [`docs/BRD_specs.md`](./docs/BRD_specs.md) for the full target architecture, and [`docs/implementation_plan_phase-1.md`](./docs/implementation_plan_phase-1.md) for the phase-by-phase build plan.

---

## Project Structure

```
lynceus/
├── api-specs/                 # OpenAPI 3.1 specifications (API-FIRST)
├── services/                  # Java Spring Boot microservices
├── ml-services/               # Python ML microservices
├── flink-jobs/                # Java Flink streaming jobs
├── frontend/                  # Turborepo monorepo (Next.js dashboard)
├── infrastructure/            # Docker Compose, NGINX, Keycloak, Helm, DB migrations
├── load-tests/                # k6 load test scenarios
├── docs/                      # Architecture docs, ADRs, runbooks
├── Makefile                   # Developer workflow commands
├── AGENTS.md                  # Project-wide instructions for AI coding agents
└── CLAUDE.md                  # Claude-specific instructions
```

---

## Contributing

See [`CONTRIBUTING.md`](./CONTRIBUTING.md) for the branching model, commit conventions, and pre-commit setup.

## License

No license has been chosen yet — all rights reserved by default until one is added.
