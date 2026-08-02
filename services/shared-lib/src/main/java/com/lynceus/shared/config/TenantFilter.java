package com.lynceus.shared.config;

import com.lynceus.shared.util.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Extracts the {@code X-Tenant-Id} header from each incoming request and populates {@link
 * TenantContext} for the duration of that request.
 *
 * <p>Phase 1 has no auth/Keycloak integration yet (see the implementation plan's Phase 1 scope
 * notes), so there is no JWT to pull {@code tenant_id} claims from. This filter is a placeholder
 * that defaults to {@code "default"} when the header is absent, keeping every downstream
 * tenant-scoped query path already wired up so Phase 2's real JWT-based tenant resolution is a
 * drop-in replacement rather than a retrofit.
 *
 * <p>Registered as a plain {@code jakarta.servlet.Filter} (via {@link OncePerRequestFilter}) rather
 * than Spring Security, since Phase 1 has no security filter chain to hook into.
 */
@Slf4j
public class TenantFilter extends OncePerRequestFilter {

  public static final String TENANT_HEADER = "X-Tenant-Id";
  public static final String DEFAULT_TENANT = "default";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String tenantId = request.getHeader(TENANT_HEADER);
    if (tenantId == null || tenantId.isBlank()) {
      tenantId = DEFAULT_TENANT;
      log.debug("No {} header present; defaulting to tenant '{}'", TENANT_HEADER, DEFAULT_TENANT);
    }
    TenantContext.set(tenantId);
    try {
      chain.doFilter(request, response);
    } finally {
      // See TenantContext's class-level Javadoc: pooled threads must never leak a
      // previous request's tenant ID into the next request they serve.
      TenantContext.clear();
    }
  }
}
