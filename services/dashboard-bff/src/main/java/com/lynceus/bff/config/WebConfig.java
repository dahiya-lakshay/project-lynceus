package com.lynceus.bff.config;

import com.lynceus.shared.config.TenantFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers shared-lib's {@link TenantFilter} as a servlet filter for this service. Identical in
 * shape to {@code transaction-service}'s {@code WebConfig} — see that class's Javadoc for why
 * {@link TenantFilter} is registered per-service rather than being a shared-lib {@code @Component}.
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
