package com.lynceus.transaction.config;

import com.lynceus.shared.config.TenantFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers shared-lib's {@link TenantFilter} as a servlet filter for this service.
 *
 * <p>{@link TenantFilter} lives in shared-lib as a plain class (not a Spring
 * bean/{@code @Component}) precisely so each consuming service controls its own registration —
 * order, URL patterns, etc. — rather than every service being forced to take it exactly the same
 * way. Ordered at {@link Ordered#HIGHEST_PRECEDENCE} so {@code TenantContext} is populated before
 * any other filter or the controller layer runs.
 */
@Configuration
public class WebConfig {

  @Bean
  public FilterRegistrationBean<TenantFilter> tenantFilter() {
    FilterRegistrationBean<TenantFilter> registration =
        new FilterRegistrationBean<>(new TenantFilter());
    registration.addUrlPatterns("/api/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }
}
