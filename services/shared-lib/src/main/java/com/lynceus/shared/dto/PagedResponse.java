package com.lynceus.shared.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Generic paginated response wrapper reused across list endpoints (e.g. the {@code
 * TransactionListResponse} shape in {@code api-specs/shared/schemas/transaction.yaml}).
 *
 * @param items the page of results
 * @param total total number of items matching the query, across all pages
 * @param page current page number (1-indexed)
 * @param pageSize number of items per page
 * @param <T> the element type
 */
public record PagedResponse<T>(
    List<T> items, int total, int page, @JsonProperty("page_size") int pageSize) {}
