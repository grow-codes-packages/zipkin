package codes.grow.packages.zipkin;

import java.util.Objects;

/**
 * A timestamped event on a Zipkin span.
 *
 * @param timestamp epoch microseconds
 * @param value event text (for example {@code cache.miss})
 */
public record ZipkinAnnotation(long timestamp, String value) {
  /**
   * Checks the value.
   *
   * @param timestamp epoch microseconds
   * @param value event text
   */
  public ZipkinAnnotation {
    Objects.requireNonNull(value, "value");
  }
}
