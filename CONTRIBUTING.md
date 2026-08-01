# Contributing to Lynceus

This project follows the workflow and standards defined in [`AGENTS.md`](./AGENTS.md) — read it before opening a branch. The short version:

## Branching

```
main          ← production-ready, tagged releases only
  └── develop ← integration branch
       ├── feature/* ← one branch per task/feature
       ├── fix/*     ← bug fixes
       └── hotfix/*  ← emergency fixes (branch from main)
```

Branch from `develop`, do your work, merge back into `develop`.

## Commits

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <description>
```

Valid types: `feat`, `fix`, `refactor`, `docs`, `ci`, `test`, `chore`, `perf`, `build`.
Valid scopes: `txn-svc`, `inference-svc`, `alert-svc`, `customer-svc`, `bff`, `frontend`, `flink`, `infra`, `ml`, `chatbot`, `api-spec`, `shared`, `ci`.

Every commit must leave the codebase compiling with tests passing — no half-finished features, no unrelated changes bundled together.

## Before submitting changes

1. Run the relevant build/test/lint commands (see `AGENTS.md` → Build & Run Commands).
2. Make sure no secrets, credentials, or `.env` files are included.
3. Install the pre-commit hooks once per clone:
   ```bash
   pre-commit install
   pre-commit install --hook-type commit-msg
   ```

## Coding standards

See `AGENTS.md` for the full per-language conventions (Java/Spring Boot, Python/FastAPI, TypeScript/Next.js) and `CLAUDE.md` for Claude-specific instructions.
