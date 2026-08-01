# AGENTS.md — Lynceus Project Instructions

> **This file is read by AI coding agents (Cursor, Windsurf, Copilot, Gemini, Claude, etc.).**
> It defines the project's architecture, coding standards, and engineering practices.
> **Follow these instructions exactly. Do not deviate unless explicitly told by the developer.**

---

## Project Overview

**Lynceus** is an enterprise-grade, event-driven, microservice-based financial transaction fraud detection platform. It uses multi-model ML inference (Isolation Forest + Autoencoder), real-time stream processing, and a multi-tenant dashboard to detect and manage fraudulent activity.

This is a **polyglot system**:

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
| LLM / Embeddings | OpenRouter (OpenAI-compatible) | — |

---

## Directory Structure

```
lynceus/
├── api-specs/                 # OpenAPI 3.1 specifications (API-FIRST)
│   ├── shared/schemas/        # Reusable schema components
│   └── *.yaml                 # Per-service API contracts
├── services/                  # Java Spring Boot microservices
│   ├── build.gradle.kts       # Root Gradle build (multi-module)
│   ├── settings.gradle.kts
│   ├── shared-lib/            # Shared Java library (DTOs, utils)
│   ├── transaction-service/
│   ├── alert-service/
│   ├── customer-service/
│   └── dashboard-bff/
├── ml-services/               # Python ML microservices
│   ├── inference-service/
│   ├── chatbot-service/
│   └── training-pipeline/
├── flink-jobs/                # Java Flink streaming jobs
│   └── feature-engineering/
├── frontend/                  # Turborepo monorepo
│   ├── turbo.json
│   ├── apps/dashboard/        # Next.js 16 app
│   └── packages/              # Shared packages (ui, types, config)
├── infrastructure/
│   ├── docker/                # Docker Compose files
│   ├── nginx/                 # NGINX gateway config
│   ├── keycloak/              # Keycloak realm exports
│   ├── helm/                  # Kubernetes Helm charts
│   └── db/migrations/         # Liquibase changelogs
├── load-tests/                # k6 load test scenarios
├── docs/                      # Architecture docs, ADRs, runbooks
├── Makefile                   # Developer workflow commands
├── AGENTS.md                  # This file
└── CLAUDE.md                  # Claude-specific instructions
```

---

## Core Principles

### 1. API-First Design

**Nothing gets coded before the OpenAPI spec exists.**

- All API contracts live in `api-specs/`
- Write the OpenAPI 3.1 YAML spec first
- Generate server stubs (Java) and client types (TypeScript) from specs
- Implementation must conform to the spec — not the other way around
- All endpoints are versioned: `/api/v1/...`

### 2. Atomic Commits

Every commit must be:
- **Self-contained**: The codebase compiles and tests pass after every single commit
- **Revertible**: `git revert <commit>` must produce a working state
- **Bisectable**: `git bisect` can identify any regression

**Never** commit:
- Half-finished features without feature flags
- Code that breaks the build
- Multiple unrelated changes in one commit

### 3. Branching Strategy

```
main          ← production-ready, tagged releases only
  └── develop ← integration branch, PRs merge here
       ├── feature/* ← one branch per task/feature
       ├── fix/*     ← bug fixes
       └── hotfix/*  ← emergency fixes (branch from main)
```

- Branch from `develop` for all work
- PR back to `develop` with squash merge
- `develop` → `main` via merge commit with release tag
- Delete feature branches after merge

### 4. Conventional Commits

All commit messages follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

**Types**: `feat`, `fix`, `refactor`, `docs`, `ci`, `test`, `chore`, `perf`, `build`

**Scopes**: `txn-svc`, `inference-svc`, `alert-svc`, `customer-svc`, `bff`, `frontend`, `flink`, `infra`, `ml`, `chatbot`, `api-spec`, `shared`

**Examples**:
```
feat(txn-svc): add transaction ingestion endpoint
fix(inference-svc): handle missing feature values in scoring
refactor(shared): extract common error response DTOs
docs(api-spec): add transaction search query parameters
ci: add Python linting to CI pipeline
test(txn-svc): add integration tests for transaction creation
chore(infra): update PostgreSQL to 17.2 in docker-compose
```

---

## Coding Standards

### Java (Spring Boot Services)

#### Package Structure
```
com.lynceus.<service>/
├── config/           # Spring @Configuration classes
├── controller/       # REST controllers (thin — delegate to service layer)
├── service/          # Business logic
├── repository/       # Spring Data JPA repositories
├── model/
│   ├── entity/       # JPA entities
│   ├── dto/          # Request/response DTOs
│   └── mapper/       # MapStruct mappers (entity ↔ DTO)
├── exception/        # Custom exceptions + @ControllerAdvice handlers
├── kafka/
│   ├── producer/     # Kafka producers
│   └── consumer/     # Kafka consumers
└── util/             # Utility classes (stateless, static methods)
```

#### Conventions
- **Java 21** — use records for DTOs, sealed interfaces for type hierarchies, virtual threads where appropriate
- **Spring Boot 3.x** — use constructor injection (never field injection with `@Autowired`)
- **Lombok** — use sparingly. Prefer records for DTOs. Use `@Slf4j` for logging. Avoid `@Data` on entities.
- **Naming**: classes `PascalCase`, methods/variables `camelCase`, constants `UPPER_SNAKE_CASE`, packages `lowercase`
- **REST controllers** must be thin — validate input, call service, return response. No business logic.
- **Service layer** contains all business logic. Services call repositories, never controllers.
- **Exceptions**: Use custom exception classes extending `RuntimeException`. Global handler via `@RestControllerAdvice`.
- **Validation**: Use `jakarta.validation` annotations on DTOs. Validate at controller boundary.
- **Null safety**: Use `Optional<T>` for return types that may be absent. Never return null from service methods.
- **Logging**: Use SLF4J (`@Slf4j`). Log at appropriate levels: `ERROR` for failures, `WARN` for degradation, `INFO` for state changes, `DEBUG` for diagnostic detail.
- **Configuration**: Externalize all config via `application.yml` + environment variables. Never hardcode URLs, credentials, or feature flags.
- **Multi-tenancy**: Every database query MUST filter by `tenant_id`. This is enforced by a `TenantContext` thread-local + JPA filters.

#### Example: Controller

```java
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Slf4j
public class TransactionController {

    private final TransactionService transactionService;

    // Separating ingestion from retrieval lets us optimize write path independently
    @PostMapping
    public ResponseEntity<TransactionResponse> createTransaction(
            @Valid @RequestBody CreateTransactionRequest request,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        var transaction = transactionService.create(request, tenantId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(transaction);
    }
}
```

#### Testing
- Unit tests: JUnit 5 + Mockito. Test service layer logic.
- Integration tests: `@SpringBootTest` + Testcontainers (PostgreSQL, Redis, Kafka).
- Test class naming: `*Test.java` for unit tests, `*IntegrationTest.java` for integration tests.
- Use `@Nested` for grouping related test cases.
- Follow Arrange-Act-Assert pattern.

---

### Python (ML Services)

#### Project Structure
```
ml-services/<service-name>/
├── src/<service_name>/
│   ├── __init__.py
│   ├── main.py            # FastAPI app factory
│   ├── api/
│   │   ├── __init__.py
│   │   ├── routes/        # Route handlers (thin — delegate to services)
│   │   ├── deps.py        # Dependency injection
│   │   └── middleware.py   # Custom middleware
│   ├── core/
│   │   ├── config.py      # Pydantic Settings
│   │   └── exceptions.py  # Custom exceptions
│   ├── models/            # ML model definitions + loading
│   ├── schemas/           # Pydantic request/response models
│   ├── services/          # Business logic
│   └── utils/
├── tests/
│   ├── unit/
│   ├── integration/
│   └── conftest.py
├── Dockerfile
├── pyproject.toml
└── README.md
```

#### Conventions
- **Python 3.12+** — use type hints everywhere, including function signatures and variables
- **FastAPI** — use dependency injection (`Depends()`), Pydantic v2 for schemas, async handlers for I/O
- **Pydantic v2** — all request/response models inherit from `BaseModel`. Use `model_validator` for complex validation.
- **Naming**: classes `PascalCase`, functions/variables `snake_case`, constants `UPPER_SNAKE_CASE`, modules `snake_case`
- **Async**: Use `async def` for I/O-bound handlers. Use `ProcessPoolExecutor` for CPU-bound ML inference.
- **Error handling**: Raise `HTTPException` or custom exceptions caught by exception handlers. Never return error details in 200 responses.
- **Configuration**: Use Pydantic `BaseSettings` with `.env` file support. Never hardcode anything.
- **Logging**: Use `structlog` for structured JSON logging. Include `tenant_id`, `request_id`, `trace_id` in all log entries.
- **Dependencies**: Managed via `pyproject.toml`. Pin all direct dependencies. Use `uv` for virtual environment and dependency resolution.

#### Example: Route Handler

```python
@router.post("/score", response_model=FraudScoreResponse, status_code=200)
async def score_transaction(
    request: ScoreTransactionRequest,
    scoring_service: ScoringService = Depends(get_scoring_service),
    tenant_id: str = Depends(get_tenant_id),
) -> FraudScoreResponse:
    """Score is computed asynchronously in a process pool to avoid blocking
    the event loop during CPU-intensive model inference."""
    return await scoring_service.score(request, tenant_id)
```

#### Testing
- Framework: `pytest` + `pytest-asyncio`
- Use `conftest.py` for fixtures
- Test naming: `test_<what>_<condition>_<expected>` (e.g., `test_score_valid_transaction_returns_fraud_probability`)
- Use `httpx.AsyncClient` for API testing
- Mock external dependencies, never make real network calls in unit tests

---

### TypeScript (Next.js Frontend)

#### Conventions
- **TypeScript strict mode** — `strict: true` in `tsconfig.json`. No `any` types.
- **React Server Components** by default (Next.js 16 SSR). Use `"use client"` only when interactivity is needed.
- **Component naming**: `PascalCase` for components, `camelCase` for hooks, `UPPER_SNAKE_CASE` for constants
- **File naming**: `kebab-case.tsx` for components, `use-kebab-case.ts` for hooks
- **State management**: React Server Components + URL search params for server state. React hooks for client state. No Redux.
- **Data fetching**: Server Components fetch data directly. Client Components use `SWR` or `React Query` for mutations/real-time.
- **Styling**: CSS Modules (`.module.css`) or vanilla CSS. No Tailwind unless explicitly approved.
- **Types**: Generate from OpenAPI specs. Never manually define types that exist in `packages/types/`.
- **Error boundaries**: Every page has an `error.tsx` and `loading.tsx`.

---

## Comment Philosophy

**Explain WHY, never WHAT.**

```java
// BAD: Increments counter by one
counter++;

// GOOD: Transaction count drives the velocity feature used by the Isolation Forest model.
// A count that resets on each scoring window ensures we capture burst patterns.
counter++;
```

```python
# BAD: Check if score is greater than threshold
if score > threshold:

# GOOD: Regulatory requirement (PCI-DSS 4.0 §6.4): all transactions exceeding 
# the risk threshold must generate an auditable alert within 30 seconds.
if score > threshold:
```

**When to comment**:
- Business logic that isn't obvious from the code
- Regulatory or compliance requirements driving a decision
- Performance trade-offs ("we use X instead of Y because...")
- Non-obvious edge cases
- Links to relevant documentation or ADRs

**When NOT to comment**:
- Self-explanatory code (good naming eliminates the need)
- Getter/setter boilerplate
- Restating what the code literally does

---

## Error Handling

### Standard Error Response

All services return errors in this format:

```json
{
  "error": {
    "code": "TRANSACTION_NOT_FOUND",
    "message": "Transaction with ID abc-123 not found",
    "details": {},
    "trace_id": "trace-xyz-789",
    "timestamp": "2026-08-02T02:00:00Z"
  }
}
```

### HTTP Status Codes

| Code | Usage |
|------|-------|
| `200` | Successful retrieval or update |
| `201` | Successful creation |
| `202` | Accepted for async processing (e.g., transaction ingestion) |
| `400` | Validation error (bad input) |
| `401` | Not authenticated |
| `403` | Not authorized (wrong role/tenant) |
| `404` | Resource not found |
| `409` | Conflict (duplicate, state conflict) |
| `422` | Unprocessable entity (valid syntax, invalid semantics) |
| `429` | Rate limited |
| `500` | Internal server error (never expose stack traces) |
| `503` | Service unavailable (downstream dependency down) |

---

## Docker Conventions

- Every service has its own `Dockerfile` in its root directory
- Use multi-stage builds (build stage + runtime stage)
- Java services: build with Gradle, run with Eclipse Temurin JRE 21-alpine
- Python services: build with uv, run with python:3.12-slim
- Next.js: build with Node, run with Node alpine + standalone output
- All images tagged as `lynceus/<service-name>:<version>`
- Never run containers as root — use non-root users
- Health check endpoints: `/actuator/health` (Spring Boot), `/health` (FastAPI)
- All services expose metrics on `/actuator/prometheus` (Java) or `/metrics` (Python)

---

## Database Conventions

- **Liquibase** for all schema changes — up-migrations only, no rollback scripts
- Changelog files in `infrastructure/db/migrations/`
- Naming: `YYYYMMDD-HH-description.yaml` (e.g., `20260802-01-create-transactions-table.yaml`)
- Every table MUST have `tenant_id` column (multi-tenancy)
- Every table MUST have `created_at` (TIMESTAMPTZ) and `updated_at` (TIMESTAMPTZ) columns
- Primary keys: UUID (`gen_random_uuid()`)
- Use PostgreSQL-specific features: JSONB, generated columns, partitioning, window functions
- Index naming: `idx_<table>_<columns>` (e.g., `idx_transactions_tenant_customer`)

---

## Environment Variables

- All configuration via environment variables
- Local dev: `.env` files (git-ignored, `.env.example` checked in)
- Naming: `LYNCEUS_<SERVICE>_<CONFIG>` (e.g., `LYNCEUS_TXN_DB_URL`, `LYNCEUS_REDIS_HOST`)
- Secrets NEVER in code, config files, or git history
- Docker Compose: use `env_file` directive

---

## Makefile Commands

Every common developer task should be runnable via `make <target>`. Check the `Makefile` in the project root for available targets. When adding new functionality, add corresponding Makefile targets.

---

## Agent Skills

As Lynceus grows across phases and touches new tools (Flink, MLflow, Keycloak realm exports, k6 load tests, Helm charts, etc.), agents should proactively add relevant skills rather than relying on general knowledge alone.

- Skills live in `.agents/skills/` (symlinked into `.claude/skills/` for Claude Code) and are installed with the [`skills`](https://skills.sh) CLI:
  ```bash
  npx skills add <repo-url> --skill <skill-name>
  # e.g. npx skills add https://github.com/vercel-labs/skills --skill find-skills
  ```
- The **`find-skills`** skill is installed in this repo — use it first to discover whether a relevant, well-maintained skill already exists before writing new one-off tooling or ad-hoc scripts (e.g. for Liquibase, Testcontainers, Flink, MLflow, Helm/k8s, k6, OpenAPI codegen).
- When a new phase of the implementation plan introduces a new technology or workflow, check for and install an applicable skill as part of that work, and mention the addition in the PR description.
- Review any skill's `SKILL.md` before relying on it — skills run with full agent permissions, so treat them like any other dependency (check the source repo, prefer well-known maintainers).
- Commit installed skills under `.agents/skills/` so the whole team and all supported agents (Claude Code, Cursor, Codex, Gemini CLI, Copilot, etc.) share the same set — don't leave skill installation as a local-only, undocumented step.

---

## Security

- All inter-service communication authenticated via JWT (Keycloak)
- NGINX validates JWT before forwarding to services
- Services extract `tenant_id` from JWT claims
- PostgreSQL RLS as a safety net (defense in depth)
- Never log sensitive data (PII, card numbers, passwords)
- Mask card numbers in API responses (show only last 4 digits)
- All API inputs validated and sanitized
- Rate limiting at NGINX layer (Redis-backed)
