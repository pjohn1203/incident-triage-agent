package com.example.triage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JSON helpers shared across the project.
 *
 * <p>Model output and tool results arrive as loosely typed {@code Map<String, Object>} trees, so
 * this class also has small null-safe accessors for walking them without casts everywhere.
 */
public final class Json {

  /** Snake_case mapper: record components like {@code minutesBeforeAlert} map to JSON keys. */
  public static final ObjectMapper MAPPER =
      new ObjectMapper()
          .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private Json() {}

  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Value is not JSON-serializable", e);
    }
  }

  public static String pretty(Object value) {
    try {
      return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Value is not JSON-serializable", e);
    }
  }

  public static Map<String, Object> readMap(String json) throws JsonProcessingException {
    return MAPPER.readValue(json, MAP_TYPE);
  }

  /** Returns {@code value} as a map, or an empty map if it is anything else. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> asMap(Object value) {
    return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }

  /** Returns {@code value} as a list, or an empty list if it is anything else. */
  @SuppressWarnings("unchecked")
  public static List<Object> asList(Object value) {
    return value instanceof List<?> l ? (List<Object>) l : List.of();
  }

  /** Returns {@code value} as a string, or {@code ""} if it is null. */
  public static String asString(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  /** Returns {@code value} as a number if it is one (or a numeric string). */
  public static Optional<Double> asNumber(Object value) {
    if (value instanceof Number n) {
      return Optional.of(n.doubleValue());
    }
    if (value instanceof String s) {
      try {
        return Optional.of(Double.parseDouble(s.trim()));
      } catch (NumberFormatException e) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  /** Walks nested maps: {@code path(m, "a", "b")} is {@code m.a.b}, or null if any hop is missing. */
  public static Object path(Map<String, ?> root, String... keys) {
    Object current = root;
    for (String key : keys) {
      if (!(current instanceof Map<?, ?> m)) {
        return null;
      }
      current = m.get(key);
    }
    return current;
  }
}
