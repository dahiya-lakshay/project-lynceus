.PHONY: help infra-up infra-down dev-all dev-all-detached stop-all build-java build-python build-frontend \
        build-all test-java test-python test-frontend test-all lint format db-migrate db-seed \
        api-validate api-generate clean

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'

# ============================================
# INFRASTRUCTURE
# ============================================
infra-up: ## Start infrastructure (PostgreSQL, Redis)
	docker compose -f infrastructure/docker/docker-compose.infra.yml up -d
	@echo "Waiting for PostgreSQL to be ready..."
	@sleep 3
	@echo "Infrastructure is up."

infra-down: ## Stop infrastructure
	docker compose -f infrastructure/docker/docker-compose.infra.yml down

dev-all: ## Start all services via Docker Compose
	docker compose -f infrastructure/docker/docker-compose.yml up --build

dev-all-detached: ## Start all services in background
	docker compose -f infrastructure/docker/docker-compose.yml up --build -d

stop-all: ## Stop all services
	docker compose -f infrastructure/docker/docker-compose.yml down

# ============================================
# BUILD
# ============================================
build-java: ## Build all Java services
	cd services && ./gradlew build -x test

build-python: ## Install Python ML services in dev mode
	cd ml-services/inference-service && uv pip install -e ".[dev]"

build-frontend: ## Build frontend
	cd frontend && pnpm install && pnpm build

build-all: build-java build-python build-frontend ## Build everything

# ============================================
# TEST
# ============================================
test-java: ## Run Java tests
	cd services && ./gradlew test

test-python: ## Run Python tests
	cd ml-services/inference-service && pytest tests/ -v

test-frontend: ## Run frontend tests
	cd frontend && pnpm test

test-all: test-java test-python test-frontend ## Run all tests

# ============================================
# LINT & FORMAT
# ============================================
lint: ## Lint all code
	cd services && ./gradlew spotlessCheck
	cd ml-services && ruff check .
	cd frontend && pnpm lint

format: ## Format all code
	cd services && ./gradlew spotlessApply
	cd ml-services && ruff format .
	cd frontend && pnpm format

# ============================================
# DATABASE
# ============================================
db-migrate: ## Run Liquibase migrations
	cd services && ./gradlew :transaction-service:update

db-seed: ## Seed development data
	python ml-services/training-pipeline/scripts/generate_synthetic_data.py --seed-db

# ============================================
# API SPECS
# ============================================
api-validate: ## Validate OpenAPI specifications
	@echo "Validating API specs..."
	npx @redocly/cli lint api-specs/*.yaml

# ============================================
# CLEAN
# ============================================
clean: ## Clean all build artifacts
	cd services && ./gradlew clean
	rm -rf frontend/.next frontend/node_modules
	find ml-services -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true
