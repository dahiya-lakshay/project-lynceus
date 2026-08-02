package com.lynceus.transaction.model.entity;

import com.lynceus.shared.dto.Channel;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists {@link Channel} using the same lowercase snake_case wire value its {@code @JsonValue}/
 * {@code @JsonCreator} pair already uses for JSON — the {@code transactions.channel} column is a
 * plain {@code VARCHAR(50)}, and reusing {@link Channel#wireValue()} instead of duplicating the
 * mapping here guarantees the DB value can never drift from what the API contract emits (e.g.
 * {@code in_store}, never the enum constant name {@code IN_STORE}).
 *
 * <p>{@code autoApply = true} since {@link Channel} is only ever used as a persisted entity
 * attribute on {@link Transaction}, so there is no ambiguous case requiring an explicit
 * {@code @Convert} on the field.
 */
@Converter(autoApply = true)
public class ChannelConverter implements AttributeConverter<Channel, String> {

  @Override
  public String convertToDatabaseColumn(Channel attribute) {
    return attribute == null ? null : attribute.wireValue();
  }

  @Override
  public Channel convertToEntityAttribute(String dbData) {
    return dbData == null ? null : Channel.fromWireValue(dbData);
  }
}
