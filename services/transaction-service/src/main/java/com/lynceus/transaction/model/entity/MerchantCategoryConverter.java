package com.lynceus.transaction.model.entity;

import com.lynceus.shared.dto.MerchantCategory;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists {@link MerchantCategory} using the same lowercase snake_case wire value its
 * {@code @JsonValue}/{@code @JsonCreator} pair already uses for JSON — the {@code
 * transactions.merchant_category} column is a plain {@code VARCHAR(100)}, and reusing {@link
 * MerchantCategory#wireValue()} instead of duplicating the mapping here guarantees the DB value can
 * never drift from what the API contract emits (e.g. {@code gas_station}, never the enum constant
 * name {@code GAS_STATION}).
 *
 * <p>{@code autoApply = true} since {@link MerchantCategory} is only ever used as a persisted
 * entity attribute on {@link Transaction}, so there is no ambiguous case requiring an explicit
 * {@code @Convert} on the field.
 */
@Converter(autoApply = true)
public class MerchantCategoryConverter implements AttributeConverter<MerchantCategory, String> {

  @Override
  public String convertToDatabaseColumn(MerchantCategory attribute) {
    return attribute == null ? null : attribute.wireValue();
  }

  @Override
  public MerchantCategory convertToEntityAttribute(String dbData) {
    return dbData == null ? null : MerchantCategory.fromWireValue(dbData);
  }
}
