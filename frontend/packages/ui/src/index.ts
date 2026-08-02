// Shared, framework-agnostic UI primitives for Lynceus frontend apps.
//
// Phase 1 has a single consumer (apps/dashboard), which defines its own
// components under components/ui/ directly. This package exists so the
// Turborepo workspace boundary is in place now — once Phase 2+ adds more
// frontend apps (e.g. an admin console) that need to share primitives like
// Badge or DataTable, those components move here without a restructure.
export {};
