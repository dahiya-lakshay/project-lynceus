package com.lynceus.transaction.exception;

import com.lynceus.shared.dto.ErrorResponse;
import com.lynceus.shared.dto.ErrorResponse.ErrorDetail;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * transaction-service-local exception handling for cases shared-lib's {@code
 * GlobalExceptionHandler} (already reviewed and merged by a different task — deliberately left
 * unmodified here) can't correctly cover on its own: its blanket
 * {@code @ExceptionHandler(Exception.class)} intercepts these before Spring's own default 400
 * mapping ever gets a chance, since {@code ExceptionHandlerExceptionResolver} picks the first
 * {@code @ControllerAdvice} bean that has *any* matching handler method for a given exception type
 * — it does not compare specificity across different advice beans, only within one.
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)} makes sure this advice is consulted before {@code
 * GlobalExceptionHandler}: without an explicit order, resolution order between advice beans that
 * don't declare one is unspecified, and {@code GlobalExceptionHandler}'s {@code Exception.class}
 * handler would otherwise silently claim both exception types below via that catch-all.
 *
 * <p>Both {@link ConstraintViolationException} and {@link HandlerMethodValidationException} are
 * handled for the {@code @Min}/{@code @Max} violations on {@code page}/{@code page_size}: with
 * {@code spring-boot-starter-validation} on the classpath, {@code @Validated} on a
 * {@code @RestController} class gets AOP-proxied by Boot's auto-configured {@code
 * MethodValidationPostProcessor}, so in practice the older AOP-based {@code
 * ConstraintViolationException} is what's actually thrown here (confirmed via {@code
 * TransactionControllerTest} against the real app context) rather than Spring Framework 6.1's newer
 * native {@code HandlerMethodValidationException} path — both are handled anyway since which one
 * fires can depend on exact autoconfiguration/versions, and neither is safe to assume without
 * verifying against the running app.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TransactionExceptionHandler {

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
    Map<String, Object> details = new LinkedHashMap<>();
    for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
      details.put(violation.getPropertyPath().toString(), violation.getMessage());
    }
    log.warn("Request parameter validation failed: {}", details);
    return errorResponse(
        HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Request validation failed", details);
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(
      HandlerMethodValidationException ex) {
    Map<String, Object> details = new LinkedHashMap<>();
    ex.getParameterValidationResults()
        .forEach(
            result -> {
              String name = result.getMethodParameter().getParameterName();
              List<String> messages =
                  result.getResolvableErrors().stream()
                      .map(MessageSourceResolvable::getDefaultMessage)
                      .toList();
              details.put(name == null ? "unknown" : name, messages);
            });
    log.warn("Request parameter validation failed: {}", details);
    return errorResponse(
        HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Request validation failed", details);
  }

  // An invalid enum query value (risk_level, merchant_category) or an unparseable
  // date_from/date_to fails Spring's ConversionService before the handler method is even
  // invoked — a 400, not the 500 the shared catch-all would otherwise produce for it.
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
    String requiredType =
        ex.getRequiredType() == null ? "unknown" : ex.getRequiredType().getSimpleName();
    String message =
        "Parameter '%s' has invalid value '%s'; expected type %s"
            .formatted(ex.getName(), ex.getValue(), requiredType);
    log.warn(message);
    return errorResponse(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message, null);
  }

  private ResponseEntity<ErrorResponse> errorResponse(
      HttpStatus status, String code, String message, Map<String, Object> details) {
    var detail =
        new ErrorDetail(code, message, details, UUID.randomUUID().toString(), Instant.now());
    return ResponseEntity.status(status).body(new ErrorResponse(detail));
  }
}
