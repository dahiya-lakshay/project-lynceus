package com.lynceus.bff.exception;

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

/**
 * dashboard-bff-local exception handling for cases shared-lib's {@code GlobalExceptionHandler}
 * can't correctly cover on its own — the same gap {@code transaction-service}'s {@code
 * TransactionExceptionHandler} documents and fixes for its own {@code page}/{@code page_size} query
 * parameters: shared-lib's blanket {@code @ExceptionHandler(Exception.class)} intercepts {@link
 * ConstraintViolationException}/{@link HandlerMethodValidationException} before Spring's own
 * default 400 mapping ever gets a chance, since {@code ExceptionHandlerExceptionResolver} picks the
 * first {@code @ControllerAdvice} bean with *any* matching handler method for a given exception
 * type rather than comparing specificity across different advice beans.
 *
 * <p>{@code DashboardController.recentTransactions} has the exact same {@code @Min}/{@code @Max}
 * validated {@code page}/{@code page_size} query parameters as {@code transaction-service}'s own
 * list endpoint, so this class needs the identical two handlers. {@code @Order(HIGHEST_PRECEDENCE)}
 * makes sure this advice is consulted before {@code GlobalExceptionHandler}, for the same reason.
 *
 * <p>Unlike {@code TransactionExceptionHandler}, there's no {@code
 * MethodArgumentTypeMismatchException} handler here — none of this controller's query parameters
 * are enums, only plain {@code int}s, so that particular failure mode doesn't apply. {@code
 * ServiceUnavailableException} (thrown by {@code TransactionClient} on any downstream
 * transaction-service failure) is already correctly mapped to a 503 by shared-lib's {@code
 * GlobalExceptionHandler} directly — no BFF-local override needed for that case.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BffExceptionHandler {

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

  private ResponseEntity<ErrorResponse> errorResponse(
      HttpStatus status, String code, String message, Map<String, Object> details) {
    var detail =
        new ErrorDetail(code, message, details, UUID.randomUUID().toString(), Instant.now());
    return ResponseEntity.status(status).body(new ErrorResponse(detail));
  }
}
