package com.lynceus.transaction.config;

import com.lynceus.shared.config.TenantFilter;
import com.lynceus.transaction.model.dto.TimeInterval;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.convert.converter.Converter;

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

  // Added by Task 8 (Dashboard BFF), for the `interval` query parameter on GET
  // /api/v1/transactions/stats/timeline. Spring MVC's default String -> Enum conversion
  // (org.springframework.core.convert.support.StringToEnumConverterFactory) calls
  // Enum.valueOf(...), which is case-sensitive against the constant's *name* ("DAY"), not
  // TimeInterval's lowercase wire value ("day") — confirmed failing against the real Spring
  // context via TransactionStatsControllerTest before this bean was added. Registering an
  // explicit Converter<String, TimeInterval> here takes precedence over that default factory for
  // this exact type pair and routes conversion through TimeInterval.fromWireValue instead, which
  // matches the same lowercase wire values used everywhere else in the API (RiskLevel,
  // MerchantCategory, Channel). An invalid value still surfaces as a
  // MethodArgumentTypeMismatchException (TransactionExceptionHandler maps that to a 400) — the
  // conversion failure path is unchanged, only which method performs the conversion is.
  //
  // Deliberately an anonymous class, not a `TimeInterval::fromWireValue` method reference: Spring
  // resolves a Converter's <S, T> type parameters at startup via reflection over
  // getClass().getGenericInterfaces(), which a lambda/method-reference's synthetic class doesn't
  // expose (confirmed failing at context startup with "Unable to determine source type <S> and
  // target type <T> for your Converter" before this was changed to an anonymous class) — an
  // anonymous class implementing Converter<String, TimeInterval> directly preserves that generic
  // signature.
  @Bean
  public Converter<String, TimeInterval> timeIntervalConverter() {
    return new Converter<String, TimeInterval>() {
      @Override
      public TimeInterval convert(String source) {
        return TimeInterval.fromWireValue(source);
      }
    };
  }
}
