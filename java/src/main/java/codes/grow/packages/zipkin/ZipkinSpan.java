package codes.grow.packages.zipkin;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * A Zipkin v2 span exactly as the server stores and returns it (all tag values are strings).
 *
 * <p>Build one for {@link ZipkinReporter#report} with {@link #builder(String, String)}: the builder
 * accepts upper-case ids, a lower-case kind and non-string tag values; {@link
 * ZipkinReporter#report} normalizes them ({@link ZipkinModel#normalize}) before sending.
 *
 * @param traceId 16 or 32 lower-case hex characters
 * @param parentId 16 lower-case hex characters; empty on a root span
 * @param id 16 lower-case hex characters
 * @param kind span kind; empty means internal work
 * @param name span name (the server lower-cases it on ingest)
 * @param timestamp start, epoch microseconds
 * @param duration duration in microseconds
 * @param localEndpoint the endpoint that recorded the span
 * @param remoteEndpoint the other side of an RPC or message
 * @param annotations timestamped events
 * @param tags string tags (insertion order kept)
 * @param debug force-store this span (B3 debug)
 * @param shared a server span that shares its id with the client span
 */
public record ZipkinSpan(
    String traceId,
    Optional<String> parentId,
    String id,
    Optional<ZipkinKind> kind,
    Optional<String> name,
    OptionalLong timestamp,
    OptionalLong duration,
    Optional<ZipkinEndpoint> localEndpoint,
    Optional<ZipkinEndpoint> remoteEndpoint,
    List<ZipkinAnnotation> annotations,
    Map<String, String> tags,
    boolean debug,
    boolean shared) {

  /**
   * Normalises nulls to empty values and copies the collections.
   *
   * @param traceId trace id
   * @param parentId parent span id
   * @param id span id
   * @param kind kind
   * @param name name
   * @param timestamp start µs
   * @param duration duration µs
   * @param localEndpoint local endpoint
   * @param remoteEndpoint remote endpoint
   * @param annotations annotations
   * @param tags tags
   * @param debug debug flag
   * @param shared shared flag
   */
  public ZipkinSpan {
    Objects.requireNonNull(traceId, "traceId");
    Objects.requireNonNull(id, "id");
    parentId = parentId == null ? Optional.empty() : parentId.filter(s -> !s.isEmpty());
    kind = kind == null ? Optional.empty() : kind;
    name = name == null ? Optional.empty() : name;
    timestamp = timestamp == null ? OptionalLong.empty() : timestamp;
    duration = duration == null ? OptionalLong.empty() : duration;
    localEndpoint = localEndpoint == null ? Optional.empty() : localEndpoint;
    remoteEndpoint = remoteEndpoint == null ? Optional.empty() : remoteEndpoint;
    annotations = annotations == null ? List.of() : List.copyOf(annotations);
    tags =
        tags == null || tags.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(tags));
  }

  /**
   * Starts a builder.
   *
   * @param traceId trace id (16 or 32 hex characters, any case)
   * @param id span id (16 hex characters, any case)
   * @return builder
   */
  public static Builder builder(String traceId, String id) {
    return new Builder(traceId, id);
  }

  /**
   * A builder pre-filled with this span.
   *
   * @return builder copy
   */
  public Builder toBuilder() {
    Builder b = new Builder(traceId, id);
    b.parentId = parentId.orElse(null);
    b.kind = kind.orElse(null);
    b.name = name.orElse(null);
    b.timestamp = timestamp.isPresent() ? timestamp.getAsLong() : null;
    b.duration = duration.isPresent() ? duration.getAsLong() : null;
    b.localEndpoint = localEndpoint.orElse(null);
    b.remoteEndpoint = remoteEndpoint.orElse(null);
    b.annotations.addAll(annotations);
    b.tags.putAll(tags);
    b.debug = debug;
    b.shared = shared;
    return b;
  }

  /** Builder of a {@link ZipkinSpan}. */
  public static final class Builder {
    private final String traceId;
    private final String id;
    private String parentId;
    private ZipkinKind kind;
    private String name;
    private Long timestamp;
    private Long duration;
    private ZipkinEndpoint localEndpoint;
    private ZipkinEndpoint remoteEndpoint;
    private final List<ZipkinAnnotation> annotations = new ArrayList<>();
    private final Map<String, String> tags = new LinkedHashMap<>();
    private boolean debug;
    private boolean shared;

    private Builder(String traceId, String id) {
      this.traceId = traceId;
      this.id = id;
    }

    /**
     * Sets the parent span id.
     *
     * @param parentId 16 hex characters, or {@code null} for a root span
     * @return this builder
     */
    public Builder parentId(String parentId) {
      this.parentId = parentId;
      return this;
    }

    /**
     * Sets the kind.
     *
     * @param kind kind, or {@code null} for internal work
     * @return this builder
     */
    public Builder kind(ZipkinKind kind) {
      this.kind = kind;
      return this;
    }

    /**
     * Sets the kind from text (case-insensitive).
     *
     * @param kind {@code client}, {@code server}, {@code producer}, {@code consumer} or {@code
     *     null}
     * @return this builder
     * @throws codes.grow.packages.zipkin.core.ValidationException for another value
     */
    public Builder kind(String kind) {
      this.kind = kind == null ? null : ZipkinKind.parse(kind);
      return this;
    }

    /**
     * Sets the span name.
     *
     * @param name name
     * @return this builder
     */
    public Builder name(String name) {
      this.name = name;
      return this;
    }

    /**
     * Sets the start time.
     *
     * @param epochMicros epoch microseconds
     * @return this builder
     */
    public Builder timestamp(long epochMicros) {
      this.timestamp = epochMicros;
      return this;
    }

    /**
     * Sets the start time.
     *
     * @param start start instant (truncated to microseconds)
     * @return this builder
     */
    public Builder timestamp(Instant start) {
      this.timestamp = start.getEpochSecond() * 1_000_000L + start.getNano() / 1_000L;
      return this;
    }

    /**
     * Sets the duration.
     *
     * @param micros microseconds
     * @return this builder
     */
    public Builder duration(long micros) {
      this.duration = micros;
      return this;
    }

    /**
     * Sets the duration.
     *
     * @param duration duration (truncated to microseconds)
     * @return this builder
     */
    public Builder duration(Duration duration) {
      this.duration = duration.toNanos() / 1_000L;
      return this;
    }

    /**
     * Sets the local endpoint.
     *
     * @param endpoint endpoint or {@code null}
     * @return this builder
     */
    public Builder localEndpoint(ZipkinEndpoint endpoint) {
      this.localEndpoint = endpoint;
      return this;
    }

    /**
     * Sets the local endpoint to a service name.
     *
     * @param serviceName service name
     * @return this builder
     */
    public Builder localServiceName(String serviceName) {
      this.localEndpoint = ZipkinEndpoint.of(serviceName);
      return this;
    }

    /**
     * Sets the remote endpoint.
     *
     * @param endpoint endpoint or {@code null}
     * @return this builder
     */
    public Builder remoteEndpoint(ZipkinEndpoint endpoint) {
      this.remoteEndpoint = endpoint;
      return this;
    }

    /**
     * Sets the remote endpoint to a service name.
     *
     * @param serviceName service name
     * @return this builder
     */
    public Builder remoteServiceName(String serviceName) {
      this.remoteEndpoint = ZipkinEndpoint.of(serviceName);
      return this;
    }

    /**
     * Adds an annotation.
     *
     * @param epochMicros epoch microseconds
     * @param value event text
     * @return this builder
     */
    public Builder annotation(long epochMicros, String value) {
      this.annotations.add(new ZipkinAnnotation(epochMicros, value));
      return this;
    }

    /**
     * Adds a tag. Non-string values are converted with {@link ZipkinModel#tagValue} ({@code true},
     * {@code 7}, {@code 1.5}, JSON for lists and maps); {@code null} removes the tag.
     *
     * @param key tag key
     * @param value tag value
     * @return this builder
     */
    public Builder tag(String key, Object value) {
      if (value == null) {
        this.tags.remove(key);
      } else {
        this.tags.put(key, ZipkinModel.tagValue(value));
      }
      return this;
    }

    /**
     * Adds tags (see {@link #tag}).
     *
     * @param tags tags
     * @return this builder
     */
    public Builder tags(Map<String, ?> tags) {
      tags.forEach(this::tag);
      return this;
    }

    /**
     * Sets the debug flag (force-store).
     *
     * @param debug flag
     * @return this builder
     */
    public Builder debug(boolean debug) {
      this.debug = debug;
      return this;
    }

    /**
     * Sets the shared flag (server span sharing the client span id).
     *
     * @param shared flag
     * @return this builder
     */
    public Builder shared(boolean shared) {
      this.shared = shared;
      return this;
    }

    /**
     * Builds the span (not validated; {@link ZipkinModel#normalize} validates).
     *
     * @return the span
     */
    public ZipkinSpan build() {
      return new ZipkinSpan(
          traceId,
          Optional.ofNullable(parentId),
          id,
          Optional.ofNullable(kind),
          Optional.ofNullable(name),
          timestamp == null ? OptionalLong.empty() : OptionalLong.of(timestamp),
          duration == null ? OptionalLong.empty() : OptionalLong.of(duration),
          Optional.ofNullable(localEndpoint),
          Optional.ofNullable(remoteEndpoint),
          annotations,
          tags,
          debug,
          shared);
    }
  }
}
