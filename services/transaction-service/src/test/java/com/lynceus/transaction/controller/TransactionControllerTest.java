package com.lynceus.transaction.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynceus.shared.dto.Channel;
import com.lynceus.shared.dto.CreateTransactionRequest;
import com.lynceus.shared.dto.MerchantCategory;
import com.lynceus.shared.dto.PagedResponse;
import com.lynceus.shared.dto.TransactionDto;
import com.lynceus.shared.dto.TransactionSummaryDto;
import com.lynceus.shared.exception.GlobalExceptionHandler;
import com.lynceus.shared.exception.ResourceNotFoundException;
import com.lynceus.transaction.exception.TransactionExceptionHandler;
import com.lynceus.transaction.service.TransactionService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code @WebMvcTest} slice for {@link TransactionController} — the service layer is mocked, this
 * only exercises request/response mapping, validation, and status codes. {@link
 * GlobalExceptionHandler} is imported explicitly since {@code @WebMvcTest} only auto-scans
 * controller-layer beans in the test's own package tree by default, not shared-lib's advice. {@link
 * TransactionExceptionHandler} is imported explicitly too (rather than relying on @WebMvcTest's
 * same-package-tree auto-detection) so its @Order(HIGHEST_PRECEDENCE) — and therefore precedence
 * over GlobalExceptionHandler's catch-all — is exercised exactly as it will be in the real
 * application context.
 */
@WebMvcTest(TransactionController.class)
@Import({GlobalExceptionHandler.class, TransactionExceptionHandler.class})
class TransactionControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @MockitoBean private TransactionService transactionService;

  @Test
  void createTransaction_withValidInput_returnsAccepted() throws Exception {
    UUID customerId = UUID.randomUUID();
    CreateTransactionRequest request =
        new CreateTransactionRequest(
            customerId,
            new BigDecimal("150.00"),
            "USD",
            "Test Store",
            MerchantCategory.ELECTRONICS,
            true,
            false,
            Channel.ONLINE,
            Map.of());

    UUID transactionId = UUID.randomUUID();
    TransactionDto response =
        new TransactionDto(
            transactionId,
            "default",
            customerId,
            new BigDecimal("150.00"),
            "USD",
            "Test Store",
            MerchantCategory.ELECTRONICS,
            true,
            false,
            Channel.ONLINE,
            Map.of(),
            null,
            null,
            Instant.now(),
            Instant.now());
    when(transactionService.create(any(CreateTransactionRequest.class))).thenReturn(response);

    mockMvc
        .perform(
            post("/api/v1/transactions")
                .header("X-Tenant-Id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value(transactionId.toString()))
        .andExpect(jsonPath("$.risk_level").doesNotExist());
  }

  @Test
  void createTransaction_withMissingAmount_returnsBadRequest() throws Exception {
    String invalidBody =
        """
        {
          "customer_id": "%s",
          "merchant_name": "Test Store",
          "merchant_category": "electronics",
          "is_online": true,
          "is_foreign": false,
          "channel": "online"
        }
        """
            .formatted(UUID.randomUUID());

    mockMvc
        .perform(
            post("/api/v1/transactions")
                .header("X-Tenant-Id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidBody))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getTransaction_withUnknownId_returnsNotFound() throws Exception {
    UUID id = UUID.randomUUID();
    when(transactionService.findById(eq(id)))
        .thenThrow(new ResourceNotFoundException("Transaction " + id + " not found"));

    mockMvc
        .perform(get("/api/v1/transactions/{id}", id).header("X-Tenant-Id", "default"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  void listTransactions_returnsPagedResponse() throws Exception {
    TransactionSummaryDto summary =
        new TransactionSummaryDto(
            UUID.randomUUID(), new BigDecimal("99.99"), "Test Store", "low", Instant.now());
    PagedResponse<TransactionSummaryDto> page = new PagedResponse<>(List.of(summary), 1, 1, 20);
    when(transactionService.list(isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
        .thenReturn(page);

    mockMvc
        .perform(get("/api/v1/transactions").header("X-Tenant-Id", "default"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].merchant_name").value("Test Store"));
  }

  // page/page_size violate their @Min/@Max bounds via Spring's method-level validation
  // (@Validated on the controller), which raises HandlerMethodValidationException on Spring
  // Boot 3.4+ — a different exception type than the @RequestBody bean-validation failures
  // GlobalExceptionHandler's MethodArgumentNotValidException handler covers. Without
  // TransactionExceptionHandler's more specific handler taking precedence,
  // GlobalExceptionHandler's blanket Exception.class catch-all claims it first and returns 500
  // instead of 400.
  @Test
  void listTransactions_withPageBelowMinimum_returnsBadRequest() throws Exception {
    mockMvc
        .perform(get("/api/v1/transactions").header("X-Tenant-Id", "default").param("page", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
  }

  @Test
  void listTransactions_withPageSizeAboveMaximum_returnsBadRequest() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/transactions").header("X-Tenant-Id", "default").param("page_size", "101"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
  }

  // An invalid risk_level fails Spring's String -> RiskLevel enum conversion before the
  // handler method is even invoked (MethodArgumentTypeMismatchException), not a bean-validation
  // failure — covered by the same TransactionExceptionHandler, same precedence-over-the-shared
  // -catch-all concern as the page/page_size cases above.
  @Test
  void listTransactions_withInvalidRiskLevel_returnsBadRequest() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/transactions")
                .header("X-Tenant-Id", "default")
                .param("risk_level", "not-a-real-risk-level"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
  }
}
