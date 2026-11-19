package codes.grow.packages.zipkin;

import codes.grow.packages.zipkin.core.Json;
import codes.grow.packages.zipkin.core.ValidationException;
import codes.grow.packages.zipkin.tracing.query.Span;
import codes.grow.packages.zipkin.tracing.query.Trace;
import codes.grow.packages.zipkin.tracing.query.TraceSummary;
import codes.grow.packages.zipkin.tracing.query.Traces;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * The Zipkin v2 JSON model: ingest normalization, encoding, decoding of query responses and the
 * conversion of Zipkin spans into the SPEC section 5 trace model.
 */
public final class ZipkinModel {
  private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{16}|[0-9a-f]{32}");
  private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");
  private static final Pattern ZEROS = Pattern.compile("0+");
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final BigDecimal JSON_INT_LIMIT = new BigDecimal("1e21");
  private static final TypeReference<LinkedHashMap<String, Object>> MAP =
      new TypeReference<LinkedHashMap<String, Object>>() {};

  private ZipkinModel() {}

  // -------------------------------------------------------------------------------------------
  // Ids and tag values

  /**
   * Lower-cases and validates a Zipkin trace id (16 or 32 hex characters, not all zero).
   *
   * @param id trace id
   * @param operation operation name for the error message
   * @return the lower-case id
   * @throws ValidationException for anything else (no request is sent)
   */
  public static String zipkinTraceId(String id, String operation) {
    String lower = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    if (!TRACE_ID.matcher(lower).matches() || ZEROS.matcher(lower).matches()) {
      throw new ValidationException(operation + ": trace id must be 16 or 32 hex characters");
    }
    return lower;
  }

  private static String spanId(String id, String what, String operation) {
    String lower = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    if (!SPAN_ID.matcher(lower).matches() || ZEROS.matcher(lower).matches()) {
      throw new ValidationException(operation + ": " + what + " must be 16 hex characters");
    }
    return lower;
  }

  /**
   * Converts a value to Zipkin's string tag form: strings as-is, booleans {@code true}/{@code
   * false}, numbers in plain decimal ({@code 7}, {@code 1.5}, {@code 2.0} → {@code 2}), lists,
   * arrays and maps as compact JSON, anything else {@code toString()}.
   *
   * @param value value
   * @return the tag text ({@code ""} for {@code null})
   */
  public static String tagValue(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof String || value instanceof Character) {
      return value.toString();
    }
    if (value instanceof Boolean
        || value instanceof Long
        || value instanceof Integer
        || value instanceof Short
        || value instanceof Byte
        || value instanceof BigInteger) {
      return value.toString();
    }
    if (value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
      return numberText((Number) value);
    }
    if (value instanceof Collection || value instanceof Map || value.getClass().isArray()) {
      return json(jsonValue(value));
    }
    return value.toString();
  }

  private static String numberText(Number n) {
    if (n instanceof BigDecimal) {
      return ((BigDecimal) n).stripTrailingZeros().toPlainString();
    }
    double v = n.doubleValue();
    if (Double.isNaN(v)) {
      return "NaN";
    }
    if (Double.isInfinite(v)) {
      return v > 0 ? "Infinity" : "-Infinity";
    }
    String repr = n instanceof Float ? Float.toString(n.floatValue()) : Double.toString(v);
    return new BigDecimal(repr).stripTrailingZeros().toPlainString();
  }

  /**
   * Converts a value into a JSON node the way {@code JSON.stringify} would: integral floating point
   * numbers become integers, non-finite numbers {@code null}.
   *
   * @param value value
   * @return the JSON node
   */
  static JsonNode jsonValue(Object value) {
    if (value == null) {
      return NODES.nullNode();
    }
    if (value instanceof JsonNode) {
      return (JsonNode) value;
    }
    if (value instanceof String || value instanceof Character) {
      return NODES.textNode(value.toString());
    }
    if (value instanceof Boolean) {
      return NODES.booleanNode((Boolean) value);
    }
    if (value instanceof Long
        || value instanceof Integer
        || value instanceof Short
        || value instanceof Byte) {
      return NODES.numberNode(((Number) value).longValue());
    }
    if (value instanceof BigInteger) {
      return NODES.numberNode((BigInteger) value);
    }
    if (value instanceof Number) {
      double v = ((Number) value).doubleValue();
      if (Double.isNaN(v) || Double.isInfinite(v)) {
        return NODES.nullNode();
      }
      BigDecimal d = new BigDecimal(numberText((Number) value));
      if (d.scale() <= 0 && d.abs().compareTo(JSON_INT_LIMIT) < 0) {
        return NODES.numberNode(d.toBigIntegerExact());
      }
      return NODES.numberNode(v);
    }
    if (value instanceof Map) {
      ObjectNode o = NODES.objectNode();
      for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
        o.set(String.valueOf(e.getKey()), jsonValue(e.getValue()));
      }
      return o;
    }
    if (value instanceof Collection) {
      ArrayNode a = NODES.arrayNode();
      for (Object item : (Collection<?>) value) {
        a.add(jsonValue(item));
      }
      return a;
    }
    if (value.getClass().isArray()) {
      ArrayNode a = NODES.arrayNode();
      int n = java.lang.reflect.Array.getLength(value);
      for (int i = 0; i < n; i++) {
        a.add(jsonValue(java.lang.reflect.Array.get(value, i)));
      }
      return a;
    }
    return NODES.textNode(value.toString());
  }

  static String json(JsonNode node) {
    try {
      return Json.mapper().writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot encode JSON", e);
    }
  }

  // -------------------------------------------------------------------------------------------
  // Ingest normalization and encoding

  /**
   * Normalizes one span for ingest (the rules Zipkin enforces, applied locally so one bad span
   * never makes the server reject the whole batch): ids lower-cased and validated, zero timestamps
   * and durations dropped, empty endpoints and names dropped, annotations checked.
   *
   * @param span span
   * @param operation operation name for the error message
   * @return the normalized span
   * @throws ValidationException naming the problem
   */
  public static ZipkinSpan normalize(ZipkinSpan span, String operation) {
    if (span == null) {
      throw new ValidationException(operation + ": span must not be null");
    }
    ZipkinSpan.Builder b =
        ZipkinSpan.builder(
            zipkinTraceId(span.traceId(), operation), spanId(span.id(), "span id", operation));
    if (span.parentId().isPresent()) {
      b.parentId(spanId(span.parentId().get(), "parentId", operation));
    }
    span.kind().ifPresent(b::kind);
    span.name().filter(n -> !n.isEmpty()).ifPresent(b::name);
    long ts = micros(span.timestamp(), "timestamp", operation);
    if (ts > 0) {
      b.timestamp(ts);
    }
    long duration = micros(span.duration(), "duration", operation);
    if (duration > 0) {
      b.duration(duration);
    }
    b.localEndpoint(endpoint(span.localEndpoint(), operation));
    b.remoteEndpoint(endpoint(span.remoteEndpoint(), operation));
    for (ZipkinAnnotation a : span.annotations()) {
      if (a.value().isEmpty()) {
        throw new ValidationException(operation + ": annotation value must be a non-empty string");
      }
      if (a.timestamp() < 0) {
        throw new ValidationException(
            operation + ": annotation timestamp must be a non-negative number of microseconds");
      }
      if (a.timestamp() == 0) {
        throw new ValidationException(operation + ": annotation timestamp is required");
      }
      b.annotation(a.timestamp(), a.value());
    }
    b.tags(span.tags());
    return b.debug(span.debug()).shared(span.shared()).build();
  }

  private static long micros(OptionalLong value, String what, String operation) {
    if (value.isEmpty()) {
      return 0;
    }
    if (value.getAsLong() < 0) {
      throw new ValidationException(
          operation + ": " + what + " must be a non-negative number of microseconds");
    }
    return value.getAsLong();
  }

  private static ZipkinEndpoint endpoint(Optional<ZipkinEndpoint> input, String operation) {
    if (input.isEmpty()) {
      return null;
    }
    ZipkinEndpoint e = input.get();
    OptionalInt port = e.port();
    if (port.isPresent() && (port.getAsInt() < 0 || port.getAsInt() > 65535)) {
      throw new ValidationException(operation + ": endpoint port must be 0..65535");
    }
    ZipkinEndpoint out =
        new ZipkinEndpoint(
            e.serviceName(),
            e.ipv4(),
            e.ipv6(),
            port.isPresent() && port.getAsInt() == 0 ? OptionalInt.empty() : port);
    return out.isEmpty() ? null : out;
  }

  /**
   * Encodes a span as Zipkin v2 JSON (field order of the server; empty fields omitted).
   *
   * @param span span
   * @return JSON object
   */
  public static ObjectNode toJson(ZipkinSpan span) {
    ObjectNode o = NODES.objectNode();
    o.put("traceId", span.traceId());
    span.parentId().ifPresent(p -> o.put("parentId", p));
    o.put("id", span.id());
    span.kind().ifPresent(k -> o.put("kind", k.name()));
    span.name().ifPresent(n -> o.put("name", n));
    span.timestamp().ifPresent(t -> o.put("timestamp", t));
    span.duration().ifPresent(d -> o.put("duration", d));
    span.localEndpoint().ifPresent(e -> o.set("localEndpoint", endpointJson(e)));
    span.remoteEndpoint().ifPresent(e -> o.set("remoteEndpoint", endpointJson(e)));
    if (!span.annotations().isEmpty()) {
      ArrayNode a = o.putArray("annotations");
      for (ZipkinAnnotation an : span.annotations()) {
        a.addObject().put("timestamp", an.timestamp()).put("value", an.value());
      }
    }
    if (!span.tags().isEmpty()) {
      ObjectNode t = o.putObject("tags");
      span.tags().forEach(t::put);
    }
    if (span.debug()) {
      o.put("debug", true);
    }
    if (span.shared()) {
      o.put("shared", true);
    }
    return o;
  }

  private static ObjectNode endpointJson(ZipkinEndpoint e) {
    ObjectNode o = NODES.objectNode();
    e.serviceName().ifPresent(v -> o.put("serviceName", v));
    e.ipv4().ifPresent(v -> o.put("ipv4", v));
    e.ipv6().ifPresent(v -> o.put("ipv6", v));
    e.port().ifPresent(v -> o.put("port", v));
    return o;
  }

  /**
   * Encodes spans as a Zipkin v2 JSON array.
   *
   * @param spans spans
   * @return JSON text
   */
  public static String toJson(List<ZipkinSpan> spans) {
    ArrayNode a = NODES.arrayNode();
    for (ZipkinSpan s : spans) {
      a.add(toJson(s));
    }
    return json(a);
  }

  // -------------------------------------------------------------------------------------------
  // Decoding responses

  /**
   * Decodes one span; {@code null} when the node is not an object with string {@code traceId} and
   * {@code id}.
   *
   * @param node JSON node
   * @return the span or {@code null}
   */
  public static ZipkinSpan spanFromJson(JsonNode node) {
    if (node == null
        || !node.isObject()
        || !node.path("traceId").isTextual()
        || !node.path("id").isTextual()) {
      return null;
    }
    ZipkinSpan.Builder b =
        ZipkinSpan.builder(node.get("traceId").asText(), node.get("id").asText());
    text(node, "parentId").ifPresent(b::parentId);
    text(node, "kind")
        .ifPresent(
            k -> {
              try {
                b.kind(ZipkinKind.parse(k));
              } catch (ValidationException ignored) {
                // unknown kind: treated as internal
              }
            });
    text(node, "name").ifPresent(b::name);
    if (node.path("timestamp").isNumber()) {
      b.timestamp(node.get("timestamp").asLong());
    }
    if (node.path("duration").isNumber()) {
      b.duration(node.get("duration").asLong());
    }
    b.localEndpoint(endpointFromJson(node.get("localEndpoint")));
    b.remoteEndpoint(endpointFromJson(node.get("remoteEndpoint")));
    for (JsonNode a : node.path("annotations")) {
      if (a.path("value").isTextual()) {
        b.annotation(a.path("timestamp").asLong(), a.get("value").asText());
      }
    }
    for (Map.Entry<String, JsonNode> e : node.path("tags").properties()) {
      JsonNode v = e.getValue();
      b.tag(e.getKey(), v.isValueNode() ? v.asText() : v.toString());
    }
    return b.debug(node.path("debug").asBoolean(false))
        .shared(node.path("shared").asBoolean(false))
        .build();
  }

  private static Optional<String> text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return v != null && v.isTextual() && !v.asText().isEmpty()
        ? Optional.of(v.asText())
        : Optional.empty();
  }

  private static ZipkinEndpoint endpointFromJson(JsonNode node) {
    if (node == null || !node.isObject()) {
      return null;
    }
    ZipkinEndpoint e =
        ZipkinEndpoint.of(
            text(node, "serviceName").orElse(null),
            text(node, "ipv4").orElse(null),
            text(node, "ipv6").orElse(null),
            node.path("port").isInt() ? node.get("port").asInt() : null);
    return e.isEmpty() ? null : e;
  }

  /**
   * Keeps the well-formed spans of a response body ({@code ZipkinSpan[]}).
   *
   * @param body JSON body
   * @return spans
   */
  public static List<ZipkinSpan> spansFromBody(JsonNode body) {
    List<ZipkinSpan> out = new ArrayList<>();
    if (body != null && body.isArray()) {
      for (JsonNode n : body) {
        ZipkinSpan s = spanFromJson(n);
        if (s != null) {
          out.add(s);
        }
      }
    }
    return List.copyOf(out);
  }

  /**
   * Keeps the well-formed traces of a response body ({@code ZipkinSpan[][]}), dropping empty ones.
   *
   * @param body JSON body
   * @return traces
   */
  public static List<List<ZipkinSpan>> tracesFromBody(JsonNode body) {
    List<List<ZipkinSpan>> out = new ArrayList<>();
    if (body != null && body.isArray()) {
      for (JsonNode n : body) {
        List<ZipkinSpan> t = spansFromBody(n);
        if (!t.isEmpty()) {
          out.add(t);
        }
      }
    }
    return List.copyOf(out);
  }

  /**
   * Decodes a string list response ({@code string[]}).
   *
   * @param body JSON body
   * @return strings (non-strings dropped)
   */
  public static List<String> stringsFromBody(JsonNode body) {
    List<String> out = new ArrayList<>();
    if (body != null && body.isArray()) {
      for (JsonNode n : body) {
        if (n.isTextual()) {
          out.add(n.asText());
        }
      }
    }
    return out;
  }

  /**
   * Decodes {@code /api/v2/dependencies}: {@code errorCount} defaults to 0, sorted by parent then
   * child.
   *
   * @param body JSON body
   * @return links
   */
  public static List<ZipkinDependencyLink> dependenciesFromBody(JsonNode body) {
    List<ZipkinDependencyLink> out = new ArrayList<>();
    if (body != null && body.isArray()) {
      for (JsonNode n : body) {
        if (n.path("parent").isTextual() && n.path("child").isTextual()) {
          out.add(
              new ZipkinDependencyLink(
                  n.get("parent").asText(),
                  n.get("child").asText(),
                  n.path("callCount").isNumber() ? n.get("callCount").asLong() : 0,
                  n.path("errorCount").isNumber() ? n.get("errorCount").asLong() : 0));
        }
      }
    }
    out.sort(
        Comparator.comparing(ZipkinDependencyLink::parent)
            .thenComparing(ZipkinDependencyLink::child));
    return List.copyOf(out);
  }

  // -------------------------------------------------------------------------------------------
  // Zipkin → SPEC section 5

  /**
   * Decodes an annotation into an event: a value of the form {@code {"<name>": {…attributes}}} (how
   * {@link ZipkinSpanExporter} encodes an event with attributes) becomes that name and attributes;
   * any other value is the event name with no attributes.
   *
   * @param annotation annotation
   * @return the event
   */
  public static Span.Event annotationToEvent(ZipkinAnnotation annotation) {
    String value = annotation.value();
    if (value.startsWith("{") && value.endsWith("}")) {
      try {
        JsonNode parsed = Json.mapper().readTree(value);
        if (parsed.isObject() && parsed.size() == 1) {
          Map.Entry<String, JsonNode> only = parsed.properties().iterator().next();
          if (only.getValue().isObject()) {
            Map<String, Object> attrs = Json.mapper().convertValue(only.getValue(), MAP);
            return new Span.Event(only.getKey(), annotation.timestamp(), attrs);
          }
        }
      } catch (JsonProcessingException ignored) {
        // not JSON: the value is the name
      }
    }
    return new Span.Event(value, annotation.timestamp(), Map.of());
  }

  /**
   * Converts a Zipkin span into the SPEC section 5 model: trace id padded to 32 characters, {@code
   * kind} absent → internal, {@code serviceName} = {@code localEndpoint.serviceName} (or {@code
   * ""}), attributes = tags, annotations → events, status from the {@code error} tag (error,
   * message = its value) or {@code otel.status_code} ({@code OK}/{@code ERROR}), no links.
   *
   * @param span Zipkin span
   * @return the normalized span
   */
  public static Span toSpan(ZipkinSpan span) {
    Map<String, String> tags = span.tags();
    long start = span.timestamp().orElse(0);
    long duration = span.duration().orElse(0);
    Span.Status status = Span.Status.UNSET;
    String error = tags.get("error");
    String otel = tags.getOrDefault("otel.status_code", "").toUpperCase(Locale.ROOT);
    if (error != null) {
      status =
          new Span.Status(
              Span.StatusCode.ERROR, error.isEmpty() ? Optional.empty() : Optional.of(error));
    } else if (otel.equals("ERROR")) {
      status = new Span.Status(Span.StatusCode.ERROR, Optional.empty());
    } else if (otel.equals("OK")) {
      status = new Span.Status(Span.StatusCode.OK, Optional.empty());
    }
    List<Span.Event> events = new ArrayList<>();
    for (ZipkinAnnotation a : span.annotations()) {
      events.add(annotationToEvent(a));
    }
    return new Span(
        span.traceId(),
        span.id(),
        span.parentId(),
        span.name().orElse(""),
        span.kind().map(k -> Span.Kind.valueOf(k.name())).orElse(Span.Kind.INTERNAL),
        span.localEndpoint().flatMap(ZipkinEndpoint::serviceName).orElse(""),
        start,
        start + duration,
        duration,
        status,
        new LinkedHashMap<String, Object>(tags),
        events,
        List.of());
  }

  /**
   * Converts the spans of one Zipkin trace into a {@link Trace} (spans sorted).
   *
   * @param spans spans of one trace
   * @return the trace, or empty for no spans
   */
  public static Optional<Trace> toTrace(List<ZipkinSpan> spans) {
    if (spans.isEmpty()) {
      return Optional.empty();
    }
    List<Span> out = new ArrayList<>();
    for (ZipkinSpan s : spans) {
      out.add(toSpan(s));
    }
    return Optional.of(new Trace(spans.get(0).traceId(), out));
  }

  /**
   * Summarises one Zipkin trace (root = the span without a parent in the trace, else the earliest).
   *
   * @param spans spans of one trace
   * @return the summary, or empty for no spans
   */
  public static Optional<TraceSummary> summarize(List<ZipkinSpan> spans) {
    return toTrace(spans).map(Traces::summarize);
  }
}
