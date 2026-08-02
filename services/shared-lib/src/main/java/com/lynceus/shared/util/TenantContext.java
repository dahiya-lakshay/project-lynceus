package com.lynceus.shared.util;

/**
 * Thread-local holder for the current request's tenant ID.
 *
 * <p>Populated by {@link com.lynceus.shared.config.TenantFilter} at the start of each request and
 * read by the service/repository layers so every database query can filter by {@code tenant_id}
 * (AGENTS.md's multi-tenancy rule) without threading the value through every method signature.
 *
 * <p><b>WHY {@link #clear()} is mandatory:</b> Servlet containers run requests on pooled threads.
 * If a thread finishes a request without clearing this value, the next request that happens to
 * reuse that pooled thread — for a *different* tenant, or an unauthenticated request — would
 * silently inherit the previous tenant's ID. In a fraud detection platform that's a tenant data
 * isolation bug, not just a bug. Callers MUST call {@link #clear()} in a {@code finally} block (see
 * {@link com.lynceus.shared.config.TenantFilter}) so the thread-local is always reset before the
 * thread returns to the pool, regardless of whether the request succeeded or threw.
 */
public final class TenantContext {

  private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

  private TenantContext() {}

  /** Sets the tenant ID for the current thread. */
  public static void set(String tenantId) {
    CURRENT_TENANT.set(tenantId);
  }

  /** Returns the tenant ID for the current thread, or {@code null} if none was set. */
  public static String get() {
    return CURRENT_TENANT.get();
  }

  /**
   * Clears the tenant ID for the current thread. MUST be called at the end of every request (e.g.
   * in a {@code finally} block) — see the class-level Javadoc for why.
   */
  public static void clear() {
    CURRENT_TENANT.remove();
  }
}
