package com.lynceus.transaction.model.mapper;

import com.lynceus.shared.dto.CreateTransactionRequest;
import com.lynceus.shared.dto.ScoreTransactionResponse;
import com.lynceus.shared.dto.TransactionDto;
import com.lynceus.shared.dto.TransactionSummaryDto;
import com.lynceus.transaction.model.entity.FraudScore;
import com.lynceus.transaction.model.entity.Transaction;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Entity &lt;-&gt; shared-lib DTO conversions for {@link Transaction} and {@link FraudScore}.
 *
 * <p>{@link #toDto} and {@link #toSummaryDto} take the {@link Transaction} and its (possibly
 * absent) {@link FraudScore} as two separate parameters rather than modeling a Transaction ->
 * FraudScore association directly on the entity: {@code fraud_scores} intentionally has no
 * cascade-delete back to {@code transactions} (see {@link FraudScore}'s Javadoc), and a transaction
 * is very often unscored (inference service unreachable) or hasn't been joined to its score yet by
 * the caller — passing {@code null} for the second parameter is simpler than modeling that as a
 * lazy, possibly-absent JPA relationship. MapStruct null-guards every {@code fraudScore.*} source
 * expression below automatically.
 */
@Mapper(componentModel = "spring")
public interface TransactionMapper {

  // isOnline/isForeign are mapped explicitly (rather than left to name-based auto-matching):
  // Transaction has a Lombok @Builder, which MapStruct prefers over plain setters when both
  // are available, and Lombok's builder methods are named after the exact field name
  // ("isOnline", not the JavaBean-stripped "online" a plain setter would use) — being
  // explicit here removes any doubt rather than depending on that resolution order.
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "tenantId", ignore = true)
  @Mapping(target = "isOnline", source = "isOnline")
  @Mapping(target = "isForeign", source = "isForeign")
  @Mapping(target = "merchantId", ignore = true)
  @Mapping(target = "locationLat", ignore = true)
  @Mapping(target = "locationLng", ignore = true)
  @Mapping(target = "countryCode", ignore = true)
  @Mapping(target = "deviceId", ignore = true)
  @Mapping(target = "ipAddress", ignore = true)
  @Mapping(target = "amountBucket", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  Transaction toEntity(CreateTransactionRequest request);

  @Mapping(target = "id", source = "transaction.id")
  @Mapping(target = "tenantId", source = "transaction.tenantId")
  @Mapping(target = "customerId", source = "transaction.customerId")
  @Mapping(target = "amount", source = "transaction.amount")
  @Mapping(target = "currency", source = "transaction.currency")
  @Mapping(target = "merchantName", source = "transaction.merchantName")
  @Mapping(target = "merchantCategory", source = "transaction.merchantCategory")
  // Reading (not building) a Transaction goes through its getters, and Lombok's boolean
  // getter/setter pair (isOnline()/setOnline(boolean)) resolves to JavaBean property name
  // "online"/"foreign" (the "is" prefix is getter-only, not part of the property name) — the
  // MapStruct-generated code below still calls transaction.isOnline() under the hood.
  @Mapping(target = "isOnline", source = "transaction.online")
  @Mapping(target = "isForeign", source = "transaction.foreign")
  @Mapping(target = "channel", source = "transaction.channel")
  @Mapping(target = "metadata", source = "transaction.metadata")
  @Mapping(target = "riskLevel", source = "fraudScore.riskLevel")
  @Mapping(target = "fraudScore", source = "fraudScore.ensembleScore")
  @Mapping(target = "createdAt", source = "transaction.createdAt")
  @Mapping(target = "updatedAt", source = "transaction.updatedAt")
  TransactionDto toDto(Transaction transaction, FraudScore fraudScore);

  @Mapping(target = "id", source = "transaction.id")
  @Mapping(target = "amount", source = "transaction.amount")
  @Mapping(target = "merchantName", source = "transaction.merchantName")
  @Mapping(target = "riskLevel", source = "fraudScore.riskLevel")
  @Mapping(target = "createdAt", source = "transaction.createdAt")
  TransactionSummaryDto toSummaryDto(Transaction transaction, FraudScore fraudScore);

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "tenantId", ignore = true)
  @Mapping(target = "transaction", ignore = true)
  @Mapping(target = "autoencoderScore", ignore = true)
  @Mapping(target = "explanation", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  FraudScore toEntity(ScoreTransactionResponse response);
}
