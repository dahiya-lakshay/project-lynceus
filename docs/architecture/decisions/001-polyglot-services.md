# ADR-001: Use Java Spring Boot for business services, Python for ML services

## Status

Accepted

## Context

Lynceus is a fraud detection platform with two fundamentally different kinds of workloads:

- **Business/transactional services** (`transaction-service`, `dashboard-bff`, and `alert-service`/`customer-service` in Phase 2): ingest transactions, enforce multi-tenancy, run Liquibase-migrated relational schemas, expose REST APIs under strict validation and error-shape contracts, and need to be safe under concurrent writes at financial-institution scale.
- **ML inference and training** (`inference-service`, `training-pipeline`, and the Phase-2+ `chatbot-service`): load a trained `scikit-learn` Isolation Forest model (`ml-services/inference-service/models/isolation_forest.joblib`), run CPU-bound feature extraction and prediction, and will later add an Autoencoder model, MLflow model registry integration, and RAG/embedding workflows.

These two workloads have different natural ecosystems. Forcing them onto one language would mean either writing ML/data-science code in Java (poor ecosystem fit — no first-class `scikit-learn`/`numpy`/pandas equivalents, weaker notebook/experimentation story) or writing the transactional core in Python (weaker compile-time guarantees for the DTOs that cross service boundaries, no direct equivalent to Spring's constructor-injected, `@Transactional`-aware service layer, and a GIL that makes the *business* services' concurrent request handling harder to reason about, not easier).

The codebase already reflects this split concretely:

- `services/shared-lib` defines the cross-service DTOs as Java **records** (e.g. `TransactionDto`, `CreateTransactionRequest`, `FraudScoreDto` in `services/shared-lib/src/main/java/com/lynceus/shared/dto/`), each with explicit `@JsonProperty` snake_case mappings so the wire format matches the OpenAPI contract in `api-specs/shared/schemas/` independent of any per-service Jackson naming strategy. `transaction-service` then uses a MapStruct `@Mapper` (`TransactionMapper`) to convert between JPA entities and these DTOs, keeping mapping code generated and boilerplate-free rather than hand-written.
- `ml-services/inference-service` defines its request/response shapes as **Pydantic v2** `BaseModel` schemas (`schemas/scoring.py`, `schemas/error.py`, `schemas/health.py`), with the same `ErrorResponse` shape mirrored from `api-specs/shared/errors.yaml` — the two ecosystems converge on the same wire contract via each language's own idiomatic validation layer rather than sharing a single type system.
- The Java side is a single **Gradle (Kotlin DSL) multi-module** build (`services/settings.gradle.kts` includes `shared-lib`, `transaction-service`, `dashboard-bff`), letting `shared-lib`'s DTOs be a compiled, versioned dependency of every Java service in one build graph. The Python side is a set of **independent `pyproject.toml` projects managed with `uv`** (`ml-services/inference-service`, `ml-services/training-pipeline`), each with its own virtual environment — there is no equivalent single build graph, because ML services don't share a Java-style common library; they share the wire contract (`api-specs/`) instead.
- Both sides converge on the same **API-first** discipline (AGENTS.md's Core Principle #1): every endpoint is specified in `api-specs/*.yaml` before implementation, so the choice of language per service is an implementation detail behind a shared, versioned contract — not something a client needs to know about.

## Decision

Use **Spring Boot 3.x on Java 21** for all core business/transactional services, and **FastAPI on Python 3.12+** for all ML inference and training services, coordinated through OpenAPI-first contracts in `api-specs/` rather than a shared type system or shared library across languages.

Concretely:

- New business logic (transaction ingestion, alerting, customer data, dashboard aggregation) goes in `services/` as a Gradle module, using constructor injection, `shared-lib` records for cross-service DTOs, and MapStruct for entity↔DTO mapping.
- New ML functionality (scoring, model training, embeddings/RAG) goes in `ml-services/` as an independent `uv`-managed FastAPI project, using Pydantic v2 for all schemas and `ProcessPoolExecutor` for CPU-bound inference to avoid blocking the async event loop.
- Cross-language contracts are defined once in `api-specs/*.yaml` (including `api-specs/shared/schemas/` and `api-specs/shared/errors.yaml`) and implemented independently on each side — Java via generated server stubs/MapStruct-mapped DTOs, Python via Pydantic models — rather than attempting a shared serialization library across the JVM and CPython.
- Stream processing (`flink-jobs/`) stays on the JVM (Java 21, Flink 2.x) rather than Python, since it sits closer to the transactional/event-driven side of the system and benefits from the same JVM tooling and type-safety expectations as the business services.

## Consequences

**Positive:**

- Each service uses the ecosystem best suited to its problem: Spring Boot's constructor injection, `@Transactional` semantics, and JPA/Liquibase integration for transactional correctness; `scikit-learn`/`numpy`/`joblib` and FastAPI's async-native design for ML serving.
- Type safety is enforced independently but consistently: Java records + `jakarta.validation` on one side, Pydantic v2 `BaseModel`s on the other — both validate at the boundary, neither allows an unvalidated payload to reach business logic.
- Teams (or agents) working on ML models don't need Java/Gradle expertise, and teams working on transactional business logic don't need Python/ML expertise — the API contract is the only thing that has to be agreed on.
- The Gradle multi-module build keeps all Java DTOs/mappers compiled and versioned together, catching cross-service breakage at compile time; the independent `uv` projects keep Python ML dependencies (which change faster and are heavier — scikit-learn, numpy, future MLflow/embedding libraries) isolated per service instead of forcing version lockstep.

**Negative / trade-offs accepted:**

- No shared type system across languages: a change to a DTO's shape must be updated in `api-specs/`, then manually re-implemented in both the Java record and the Pydantic model — there is no codegen step yet that keeps both in perfect lockstep (see `make api-generate`, currently scoped to server stubs/client types, not cross-language DTO sync). This is an accepted ongoing discipline cost, mitigated by API-first contracts and the shared `ErrorResponse`/status-code conventions in AGENTS.md.
- Two separate dependency ecosystems (Gradle/Maven Central vs. `uv`/PyPI) means two sets of lockfiles, two sets of CI lint/test/build steps, and two Dockerfile patterns (multi-stage Gradle build → Temurin JRE Alpine runtime; multi-stage `uv`-installed wheel → `python:3.12-slim` runtime) to maintain instead of one.
- Operationally, running the full stack locally means building and healthchecking both a JVM toolchain and a Python toolchain (plus Node for the frontend) — first-time `make dev-all` builds take materially longer than a single-language stack would, as observed when standing up this Phase 1 environment (Gradle's fresh dependency resolution was the dominant cost).
