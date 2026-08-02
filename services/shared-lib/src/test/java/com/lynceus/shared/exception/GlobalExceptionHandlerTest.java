package com.lynceus.shared.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lynceus.shared.dto.ErrorResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void handleResourceNotFound_returns404WithErrorCode() {
    var ex = new ResourceNotFoundException("Transaction abc-123 not found");

    ResponseEntity<ErrorResponse> response = handler.handleResourceNotFound(ex);

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals("RESOURCE_NOT_FOUND", response.getBody().error().code());
    assertEquals("Transaction abc-123 not found", response.getBody().error().message());
    assertNotNull(response.getBody().error().traceId());
    assertNotNull(response.getBody().error().timestamp());
  }

  @Test
  void handleValidation_returns422WithDetails() {
    var ex = new ValidationException("amount must be positive", Map.of("field", "amount"));

    ResponseEntity<ErrorResponse> response = handler.handleValidation(ex);

    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals("VALIDATION_FAILED", response.getBody().error().code());
    assertEquals(Map.of("field", "amount"), response.getBody().error().details());
  }

  @Test
  void handleServiceUnavailable_returns503() {
    var ex = new ServiceUnavailableException("inference-service unreachable");

    ResponseEntity<ErrorResponse> response = handler.handleServiceUnavailable(ex);

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals("SERVICE_UNAVAILABLE", response.getBody().error().code());
  }

  @Test
  void handleUnexpected_returns500WithoutLeakingExceptionDetails() {
    var ex = new RuntimeException("sensitive internal detail: db password=hunter2");

    ResponseEntity<ErrorResponse> response = handler.handleUnexpected(ex);

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals("INTERNAL_SERVER_ERROR", response.getBody().error().code());
    // The generic message must never contain the original exception's message.
    assertTrue(
        response.getBody().error().message() != null
            && !response.getBody().error().message().contains("hunter2"));
  }

  @Test
  void handleMethodArgumentNotValid_returns400() {
    var fieldError = new FieldError("createTransactionRequest", "amount", "must be positive");
    var bindingResult = mock(BindingResult.class);
    when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));
    var ex = mock(MethodArgumentNotValidException.class);
    when(ex.getBindingResult()).thenReturn(bindingResult);

    ResponseEntity<ErrorResponse> response = handler.handleMethodArgumentNotValid(ex);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals("BAD_REQUEST", response.getBody().error().code());
    assertEquals(Map.of("amount", "must be positive"), response.getBody().error().details());
  }
}
