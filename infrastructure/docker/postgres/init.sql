-- Enable extensions required by Lynceus.
-- pgvector: vector similarity search for RAG chatbot embeddings
-- pg_stat_statements: query performance monitoring for identifying slow queries
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Development-only: create a default tenant for local development.
-- Production tenants are managed by Keycloak realm provisioning (Phase 2+).
