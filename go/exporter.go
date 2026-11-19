package zipkin

import (
	"bytes"
	"context"
	"encoding/json"
	"net/netip"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/trace"
)

// SpanExporter is an OpenTelemetry sdktrace.SpanExporter that sends spans to Zipkin as
// v2 JSON through a Reporter (SDKSpanToZipkin describes the mapping). It is the
// exporter InitTracing uses for ProtocolNative; it does not depend on the deprecated
// go.opentelemetry.io/otel/exporters/zipkin module.
type SpanExporter struct {
	reporter *Reporter
	owned    bool
	stopped  atomic.Bool
}

var _ sdktrace.SpanExporter = (*SpanExporter)(nil)

// NewSpanExporter returns an exporter that reports through r. Shutdown does not close
// r (the caller owns it).
func NewSpanExporter(r *Reporter) *SpanExporter { return &SpanExporter{reporter: r} }

// ExportSpans converts and reports spans (one POST per MaxBatchSize spans). After
// Shutdown it drops spans and returns nil.
func (e *SpanExporter) ExportSpans(ctx context.Context, spans []sdktrace.ReadOnlySpan) error {
	if e.stopped.Load() || len(spans) == 0 {
		return nil
	}
	out := make([]ZipkinSpan, len(spans))
	for i, s := range spans {
		out[i] = SDKSpanToZipkin(s)
	}
	return e.reporter.Report(ctx, out)
}

// Shutdown stops the exporter and releases the reporter when the exporter owns it
// (NewExporter). It is idempotent.
func (e *SpanExporter) Shutdown(ctx context.Context) error {
	if e.stopped.CompareAndSwap(false, true) && e.owned {
		_ = e.reporter.Close()
	}
	return ContextError("zipkin.exporter.shutdown", ctx.Err())
}

// UnknownService is the local service name used when the resource has no service.name.
const UnknownService = "unknown_service"

// ResourceTagKeys are the resource attributes copied into every span's tags (span
// attributes with the same key win). Other resource attributes are not mapped;
// service.name becomes localEndpoint.serviceName.
var ResourceTagKeys = []string{"service.version", "service.namespace", "deployment.environment.name"}

// remoteServiceKeys name the remote service of a CLIENT or PRODUCER span, by precedence.
var remoteServiceKeys = []string{"peer.service", "server.address", "net.peer.name", "network.peer.address"}

// remotePortKeys carry the remote port of a CLIENT or PRODUCER span, by precedence.
var remotePortKeys = []string{"server.port", "network.peer.port", "net.peer.port"}

// SDKSpanToZipkin converts an ended OpenTelemetry span to a Zipkin v2 span (the
// OpenTelemetry → Zipkin mapping, identical in every language of this SDK):
//
//   - traceId / id from the span context; parentId only when the parent is valid.
//   - kind SERVER/CLIENT/PRODUCER/CONSUMER 1:1, INTERNAL → omitted; name as is (the
//     server lower-cases it).
//   - timestamp = start in epoch µs (truncated); duration = end − start in µs, rounded,
//     at least 1.
//   - localEndpoint.serviceName = resource service.name, else "unknown_service".
//   - remoteEndpoint (CLIENT/PRODUCER only): serviceName = first of peer.service,
//     server.address, net.peer.name, network.peer.address; ipv4/ipv6 =
//     network.peer.address when it is an IP; port = first of server.port,
//     network.peer.port, net.peer.port; omitted without a service name or IP.
//   - tags = the resource attributes in ResourceTagKeys, then the span attributes
//     (TagValue: booleans "true", numbers decimal, arrays JSON), then otel.scope.name /
//     otel.scope.version, otel.dropped_attributes_count / otel.dropped_events_count
//     when > 0, and the status: ERROR → otel.status_code=ERROR + error=<description or
//     "">, OK → otel.status_code=OK.
//   - annotations = events: the event name, or {"<name>":{...attributes as tags}} when
//     the event has attributes (AnnotationToEvent reverses it).
//   - debug, shared, links and trace state are not mapped.
func SDKSpanToZipkin(s sdktrace.ReadOnlySpan) ZipkinSpan {
	sc := s.SpanContext()
	start, end := s.StartTime(), s.EndTime()
	out := ZipkinSpan{
		TraceID:       sc.TraceID().String(),
		ID:            sc.SpanID().String(),
		Name:          s.Name(),
		Timestamp:     start.UnixMicro(),
		Duration:      max(end.Sub(start).Round(time.Microsecond).Microseconds(), 1),
		LocalEndpoint: &Endpoint{ServiceName: UnknownService},
		Tags:          map[string]string{},
	}
	if p := s.Parent(); p.SpanID().IsValid() {
		out.ParentID = p.SpanID().String()
	}
	switch s.SpanKind() {
	case trace.SpanKindServer:
		out.Kind = KindServer
	case trace.SpanKindClient:
		out.Kind = KindClient
	case trace.SpanKindProducer:
		out.Kind = KindProducer
	case trace.SpanKindConsumer:
		out.Kind = KindConsumer
	}
	if r := s.Resource(); r != nil {
		set := r.Set()
		if v, ok := set.Value("service.name"); ok && v.AsString() != "" {
			out.LocalEndpoint.ServiceName = v.AsString()
		}
		for _, k := range ResourceTagKeys {
			if v, ok := set.Value(attribute.Key(k)); ok {
				out.Tags[k] = otelTagValue(v)
			}
		}
	}
	attrs := s.Attributes()
	for _, kv := range attrs {
		out.Tags[string(kv.Key)] = otelTagValue(kv.Value)
	}
	if out.Kind == KindClient || out.Kind == KindProducer {
		out.RemoteEndpoint = remoteEndpoint(attrs)
	}
	if scope := s.InstrumentationScope(); scope.Name != "" {
		out.Tags["otel.scope.name"] = scope.Name
		if scope.Version != "" {
			out.Tags["otel.scope.version"] = scope.Version
		}
	}
	if n := s.DroppedAttributes(); n > 0 {
		out.Tags["otel.dropped_attributes_count"] = strconv.Itoa(n)
	}
	if n := s.DroppedEvents(); n > 0 {
		out.Tags["otel.dropped_events_count"] = strconv.Itoa(n)
	}
	switch st := s.Status(); st.Code {
	case codes.Error:
		out.Tags["otel.status_code"] = "ERROR"
		out.Tags["error"] = st.Description
	case codes.Ok:
		out.Tags["otel.status_code"] = "OK"
	}
	for _, ev := range s.Events() {
		out.Annotations = append(out.Annotations, Annotation{Timestamp: ev.Time.UnixMicro(), Value: eventValue(ev)})
	}
	if len(out.Tags) == 0 {
		out.Tags = nil
	}
	return out
}

// remoteEndpoint derives the remote endpoint of a CLIENT/PRODUCER span (nil when it
// has neither a service name nor an IP).
func remoteEndpoint(attrs []attribute.KeyValue) *Endpoint {
	values := make(map[string]attribute.Value, len(attrs))
	for _, kv := range attrs {
		values[string(kv.Key)] = kv.Value
	}
	out := Endpoint{}
	for _, k := range remoteServiceKeys {
		if v, ok := values[k]; ok && v.Type() == attribute.STRING && v.AsString() != "" {
			out.ServiceName = v.AsString()
			break
		}
	}
	if v, ok := values["network.peer.address"]; ok && v.Type() == attribute.STRING {
		if ip, err := netip.ParseAddr(v.AsString()); err == nil {
			if ip.Is4() {
				out.IPv4 = v.AsString()
			} else {
				out.IPv6 = v.AsString()
			}
		}
	}
	for _, k := range remotePortKeys {
		v, ok := values[k]
		if !ok {
			continue
		}
		port := int64(-1)
		switch v.Type() {
		case attribute.INT64:
			port = v.AsInt64()
		case attribute.FLOAT64:
			if f := v.AsFloat64(); f == float64(int64(f)) {
				port = int64(f)
			}
		case attribute.STRING:
			if n, err := strconv.ParseInt(strings.TrimSpace(v.AsString()), 10, 64); err == nil {
				port = n
			}
		}
		if port > 0 && port <= 65535 {
			out.Port = int(port)
			break
		}
	}
	if out.ServiceName == "" && out.IPv4 == "" && out.IPv6 == "" {
		return nil
	}
	return &out
}

// marshalJSON encodes v without HTML escaping.
func marshalJSON(v any) string {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return ""
	}
	return string(bytes.TrimRight(buf.Bytes(), "\n"))
}

// otelTagValue converts an attribute value to Zipkin's string form.
func otelTagValue(v attribute.Value) string {
	switch v.Type() {
	case attribute.STRING:
		return v.AsString()
	case attribute.BOOL, attribute.INT64, attribute.FLOAT64:
		return TagValue(v.AsInterface())
	case attribute.BOOLSLICE, attribute.INT64SLICE, attribute.FLOAT64SLICE, attribute.STRINGSLICE:
		return marshalJSON(v.AsInterface())
	}
	return v.String()
}

// eventValue encodes an event as an annotation value.
func eventValue(ev sdktrace.Event) string {
	if len(ev.Attributes) == 0 {
		return ev.Name
	}
	attrs := make(map[string]string, len(ev.Attributes))
	for _, kv := range ev.Attributes {
		attrs[string(kv.Key)] = otelTagValue(kv.Value)
	}
	return marshalJSON(map[string]any{ev.Name: attrs})
}
