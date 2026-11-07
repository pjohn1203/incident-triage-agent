package com.example.triage.heuristics;

import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds the affected service in free-text incident reports (used by the baseline model). */
public final class ServiceExtractor {

  private static final Pattern SERVICE_LIKE =
      Pattern.compile("\\b[a-z][a-z0-9]*(?:-[a-z0-9]+)*-(?:service|api|svc)\\b");

  private ServiceExtractor() {}

  /**
   * Tries, in order: a known service name ("payments-api"), anything shaped like a service name
   * ("billing-service"), then a known service's short name ("checkout" for "checkout-service").
   */
  public static Optional<String> extract(String text, Collection<String> knownServices) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }
    String lower = text.toLowerCase(Locale.ROOT);

    Optional<String> exact =
        knownServices.stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .filter(s -> containsWord(lower, s))
            .findFirst();
    if (exact.isPresent()) {
      return exact;
    }

    Matcher m = SERVICE_LIKE.matcher(lower);
    if (m.find()) {
      return Optional.of(m.group());
    }

    return knownServices.stream()
        .filter(s -> containsWord(lower, shortName(s)))
        .findFirst();
  }

  private static String shortName(String service) {
    return service.replaceFirst("-(service|api|svc)$", "");
  }

  private static boolean containsWord(String haystack, String word) {
    return Pattern.compile("(?<![a-z0-9-])" + Pattern.quote(word) + "(?![a-z0-9-])")
        .matcher(haystack)
        .find();
  }
}
