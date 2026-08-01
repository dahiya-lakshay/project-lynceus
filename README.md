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

Prerequisites: Docker + Docker Compose v2, Java 21 (Temurin), Python 3.12+ with `uv`, Node 22 LTS with `pnpm`.

```bash
# Infrastructure only (PostgreSQL, Redis)
make infra-up

# Everything (all services + infrastructure), via Docker Compose
make dev-all
```

Then open `http://localhost:8080` for the dashboard (routed through NGINX).

See [`AGENTS.md`](./AGENTS.md) and [`CLAUDE.md`](./CLAUDE.md) for the full set of build, test, lint, and database commands per service.

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
