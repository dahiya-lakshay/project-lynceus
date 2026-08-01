# CLAUDE.md — Claude-Specific Project Instructions

> **Read `AGENTS.md` first.** This file contains Claude-specific instructions that supplement the general agent instructions.

---

## Project Context

You are working on **Lynceus**, a production-grade fraud detection platform. This is NOT a toy project — treat every decision as if it's going to production at a financial institution. When in doubt, choose the more robust option.

The project is built in phases. **Check the current phase** before making changes — don't introduce Phase 3 dependencies into Phase 1 code. The implementation plan tracks which phase we're in.

---

## Build & Run Commands

### Infrastructure
```bash
# Start infrastructure only (PostgreSQL, Redis, Kafka, Keycloak)
make infra-up

# Stop infrastructure
make infra-down

# Start everything (all services + infrastructure)
make dev-all

# View logs
docker compose -f infrastructure/docker/docker-compose.yml logs -f <service-name>
```

### Java Services
```bash
# Build all Java services
cd services && ./gradlew build

# Build a specific service
cd services && ./gradlew :transaction-service:build

# Run a specific service locally (with dependencies running in Docker)
cd services && ./gradlew :transaction-service:bootRun

# Run tests
cd services && ./gradlew test

# Run a specific service's tests
cd services && ./gradlew :transaction-service:test

# Lint / format check
cd services && ./gradlew spotlessCheck

# Auto-format
cd services && ./gradlew spotlessApply
```

### Python ML Services
```bash
# Set up virtual environment (use uv)
cd ml-services/inference-service && uv venv && uv pip install -e ".[dev]"

# Run inference service locally
cd ml-services/inference-service && uvicorn src.inference_service.main:app --reload --port 8001

# Run tests
cd ml-services && pytest

# Run a specific service's tests
cd ml-services/inference-service && pytest tests/

# Lint
cd ml-services && ruff check .

# Format
cd ml-services && ruff format .

# Type check
cd ml-services/inference-service && mypy src/
```

### Frontend
```bash
# Install dependencies
cd frontend && pnpm install

# Run dev server
cd frontend && pnpm dev

# Build
cd frontend && pnpm build

# Lint
cd frontend && pnpm lint

# Type check
cd frontend && pnpm type-check
```

### Database
```bash
# Run Liquibase migrations
make db-migrate

# Seed development data
make db-seed

# Connect to PostgreSQL
docker exec -it lynceus-postgres psql -U lynceus -d lynceus
```

### API Specs
```bash
# Validate all OpenAPI specs
make api-validate

# Generate code from specs
make api-generate
```

---

## Git Workflow

### Before Starting Any Work
```bash
# Always branch from develop
git checkout develop
git pull origin develop
git checkout -b feature/<descriptive-name>
```

### Commit Conventions
```bash
# Format: <type>(<scope>): <description>
git commit -m "feat(txn-svc): add transaction ingestion endpoint"
git commit -m "fix(inference-svc): handle NaN values in feature vector"
git commit -m "test(txn-svc): add integration tests for bulk ingestion"
git commit -m "docs(api-spec): update fraud score response schema"
git commit -m "chore(infra): bump PostgreSQL to 17.2"
```

### Valid Scopes
`txn-svc`, `inference-svc`, `alert-svc`, `customer-svc`, `bff`, `frontend`, `flink`, `infra`, `ml`, `chatbot`, `api-spec`, `shared`, `ci`

### Commit Checklist
Before committing, verify:
1. Code compiles/builds: `./gradlew build` or equivalent
2. Tests pass: `./gradlew test` or `pytest`
3. Linting passes: `./gradlew spotlessCheck` or `ruff check .`
4. Commit message follows conventional commits
5. No secrets, credentials, or `.env` files in the commit
6. No unrelated changes bundled together

---

## Key Coding Rules

### DO:
- Write type-safe code everywhere (Java generics, Python type hints, TypeScript strict mode)
- Use constructor injection in Spring Boot (never `@Autowired` on fields)
- Use `async def` for I/O-bound FastAPI handlers
- Use `ProcessPoolExecutor` for CPU-bound ML inference (Python GIL)
- Include `tenant_id` in EVERY database query
- Write comments that explain WHY, never WHAT
- Use Pydantic v2 `BaseModel` for all Python request/response schemas
- Use Java records for immutable DTOs
- Use MapStruct for entity ↔ DTO mapping in Java
- Handle errors explicitly — never swallow exceptions silently
- Log with structured fields: `tenant_id`, `request_id`, `trace_id`
- Use `@Transactional` intentionally — know exactly which methods need transactions

### DON'T:
- Don't use `@Autowired` on fields — use constructor injection
- Don't use `@Data` on JPA entities — causes issues with lazy loading and equals/hashCode
- Don't use `any` type in TypeScript
- Don't use `SELECT *` in SQL queries
- Don't hardcode URLs, ports, credentials, or feature flags
- Don't write rollback/down migrations — we only do up-migrations
- Don't return null from service methods — use `Optional<T>` in Java
- Don't catch `Exception` generically — catch specific exceptions
- Don't commit `.env` files, secrets, or generated code
- Don't add Tailwind CSS unless explicitly told to
- Don't import things you don't use
- Don't leave `TODO` comments without a linked issue/ticket
- Don't skip writing tests — every feature needs tests

---

## File Creation Checklist

When creating a new file, always include:

### Java Files
- Package declaration
- Appropriate Spring annotations
- SLF4J logger (`@Slf4j`)
- Constructor injection for dependencies
- Javadoc on public classes (brief, purposeful)

### Python Files
- Module docstring explaining the file's purpose
- Type hints on all function signatures
- `__all__` export list in `__init__.py`

### TypeScript Files
- Explicit return types on exported functions
- Props interface for every component
- No `any` types

### YAML/Config Files
- Comment block at top explaining purpose
- Group related settings with blank lines

---

## Testing Philosophy

- **Unit tests**: Test business logic in isolation. Mock external dependencies.
- **Integration tests**: Test service boundaries (API → DB, API → Kafka). Use Testcontainers.
- **Contract tests**: Validate API implementations match OpenAPI specs.
- **No flaky tests**: If a test is flaky, fix it or delete it. Never skip it.

### Test Naming
```java
// Java: describe what is being tested
@Test
void createTransaction_withValidInput_returnsAccepted() { }

@Test
void createTransaction_withMissingAmount_throwsValidationException() { }
```

```python
# Python: test_<what>_<condition>_<expected>
def test_score_transaction_with_valid_features_returns_probability():
    pass

def test_score_transaction_with_missing_features_raises_validation_error():
    pass
```

---

## When You're Unsure

1. **Check `AGENTS.md`** for project-wide standards
2. **Check existing code** for patterns already established
3. **Check `api-specs/`** for the API contract before implementing an endpoint
4. **Check `docs/architecture/decisions/`** for ADRs that explain past decisions
5. **Ask the developer** — don't guess on architectural decisions

---

## Performance Considerations

- Java services: Profile with JFR (Java Flight Recorder) if performance issues arise
- Python services: Profile with `cProfile` or `py-spy`
- Database: Check `pg_stat_statements` for slow queries
- Use Redis caching for hot data (cache-aside pattern, TTL-based invalidation)
- Batch database writes where possible
- Use database connection pooling (HikariCP for Java, asyncpg pool for Python)
