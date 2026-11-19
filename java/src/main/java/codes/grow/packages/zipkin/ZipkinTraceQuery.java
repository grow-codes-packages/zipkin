package codes.grow.packages.zipkin;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Filter of {@link ZipkinQueryClient#getTraces} ({@code GET /api/v2/traces}). Every field is
 * optional; unset fields use the server defaults ({@code endTs} = now, {@code lookback} = 24 h,
 * {@code limit} = 10). {@code annotationQuery}, {@code tags} and {@code annotations} are combined
 * with {@code " and "} (Zipkin supports AND only).
 */
public final class ZipkinTraceQuery {
  private final String serviceName;
  private final String remoteServiceName;
  private final String spanName;
  private final String annotationQuery;
  private final Map<String, String> tags;
  private final List<String> annotations;
  private final Long minDurationMicros;
  private final Long maxDurationMicros;
  private final Instant endTs;
  private final Duration lookback;
  private final Integer limit;

  private ZipkinTraceQuery(Builder b) {
    this.serviceName = b.serviceName;
    this.remoteServiceName = b.remoteServiceName;
    this.spanName = b.spanName;
    this.annotationQuery = b.annotationQuery;
    this.tags = Collections.unmodifiableMap(new LinkedHashMap<>(b.tags));
    this.annotations = List.copyOf(b.annotations);
    this.minDurationMicros = b.minDurationMicros;
    this.maxDurationMicros = b.maxDurationMicros;
    this.endTs = b.endTs;
    this.lookback = b.lookback;
    this.limit = b.limit;
  }

  /**
   * Starts an empty query (server defaults).
   *
   * @return builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Local service name (matched case-insensitively).
   *
   * @return the name, or empty
   */
  public Optional<String> serviceName() {
    return Optional.ofNullable(serviceName);
  }

  /**
   * Remote service name ({@code remoteEndpoint.serviceName}) seen on {@code serviceName}'s spans.
   *
   * @return the name, or empty
   */
  public Optional<String> remoteServiceName() {
    return Optional.ofNullable(remoteServiceName);
  }

  /**
   * Span name.
   *
   * @return the name, or empty
   */
  public Optional<String> spanName() {
    return Optional.ofNullable(spanName);
  }

  /**
   * Raw Zipkin annotation query, for example {@code http.method=GET and error}.
   *
   * @return the query, or empty
   */
  public Optional<String> annotationQuery() {
    return Optional.ofNullable(annotationQuery);
  }

  /**
   * Exact tag matches ({@code key=value}).
   *
   * @return tags
   */
  public Map<String, String> tags() {
    return tags;
  }

  /**
   * Bare terms: a tag key or an annotation value.
   *
   * @return terms
   */
  public List<String> annotations() {
    return annotations;
  }

  /**
   * Minimum span duration in microseconds.
   *
   * @return the duration, or empty
   */
  public OptionalLong minDurationMicros() {
    return minDurationMicros == null ? OptionalLong.empty() : OptionalLong.of(minDurationMicros);
  }

  /**
   * Maximum span duration in microseconds.
   *
   * @return the duration, or empty
   */
  public OptionalLong maxDurationMicros() {
    return maxDurationMicros == null ? OptionalLong.empty() : OptionalLong.of(maxDurationMicros);
  }

  /**
   * End of the window.
   *
   * @return the instant, or empty (server now)
   */
  public Optional<Instant> endTs() {
    return Optional.ofNullable(endTs);
  }

  /**
   * Window length before {@code endTs}.
   *
   * @return the duration, or empty (server default 24 h)
   */
  public Optional<Duration> lookback() {
    return Optional.ofNullable(lookback);
  }

  /**
   * Maximum number of traces.
   *
   * @return the limit, or empty (server default 10)
   */
  public OptionalInt limit() {
    return limit == null ? OptionalInt.empty() : OptionalInt.of(limit);
  }

  /** Builder of a {@link ZipkinTraceQuery} (validated by {@link ZipkinQueryClient#getTraces}). */
  public static final class Builder {
    private String serviceName;
    private String remoteServiceName;
    private String spanName;
    private String annotationQuery;
    private final Map<String, String> tags = new LinkedHashMap<>();
    private final List<String> annotations = new ArrayList<>();
    private Long minDurationMicros;
    private Long maxDurationMicros;
    private Instant endTs;
    private Duration lookback;
    private Integer limit;

    private Builder() {}

    /**
     * Local service name.
     *
     * @param serviceName name
     * @return this
     */
    public Builder serviceName(String serviceName) {
      this.serviceName = serviceName;
      return this;
    }

    /**
     * Remote service name.
     *
     * @param remoteServiceName name
     * @return this
     */
    public Builder remoteServiceName(String remoteServiceName) {
      this.remoteServiceName = remoteServiceName;
      return this;
    }

    /**
     * Span name.
     *
     * @param spanName name
     * @return this
     */
    public Builder spanName(String spanName) {
      this.spanName = spanName;
      return this;
    }

    /**
     * Raw annotation query.
     *
     * @param annotationQuery query such as {@code http.method=GET and error}
     * @return this
     */
    public Builder annotationQuery(String annotationQuery) {
      this.annotationQuery = annotationQuery;
      return this;
    }

    /**
     * Adds an exact tag match. A key or value containing {@code " and "} (or a key containing
     * {@code =}) cannot be expressed in Zipkin's syntax: that term is matched client-side after the
     * query (so fewer than {@code limit} traces may come back).
     *
     * @param key tag key
     * @param value tag value
     * @return this
     */
    public Builder tag(String key, String value) {
      this.tags.put(key, value);
      return this;
    }

    /**
     * Adds exact tag matches (see {@link #tag}).
     *
     * @param tags tags
     * @return this
     */
    public Builder tags(Map<String, String> tags) {
      this.tags.putAll(tags);
      return this;
    }

    /**
     * Adds a bare term: a tag key or an annotation value ({@code error}, {@code cache.miss}).
     *
     * @param term term
     * @return this
     */
    public Builder annotation(String term) {
      this.annotations.add(term);
      return this;
    }

    /**
     * Minimum span duration.
     *
     * @param micros microseconds
     * @return this
     */
    public Builder minDurationMicros(long micros) {
      this.minDurationMicros = micros;
      return this;
    }

    /**
     * Maximum span duration (needs a minimum).
     *
     * @param micros microseconds
     * @return this
     */
    public Builder maxDurationMicros(long micros) {
      this.maxDurationMicros = micros;
      return this;
    }

    /**
     * End of the window.
     *
     * @param endTs instant
     * @return this
     */
    public Builder endTs(Instant endTs) {
      this.endTs = endTs;
      return this;
    }

    /**
     * Window length before {@code endTs}.
     *
     * @param lookback positive duration
     * @return this
     */
    public Builder lookback(Duration lookback) {
      this.lookback = lookback;
      return this;
    }

    /**
     * Maximum number of traces.
     *
     * @param limit positive limit
     * @return this
     */
    public Builder limit(int limit) {
      this.limit = limit;
      return this;
    }

    /**
     * Builds the query.
     *
     * @return query
     */
    public ZipkinTraceQuery build() {
      return new ZipkinTraceQuery(this);
    }
  }
}
