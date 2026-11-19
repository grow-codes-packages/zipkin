package zipkin

import (
	"bytes"
	"encoding/json"
	"fmt"
	"sort"
	"strconv"
	"strings"
)

// Kind is the kind of a Zipkin span. The empty kind means local (internal) work.
type Kind string

// Zipkin span kinds.
const (
	KindClient   Kind = "CLIENT"
	KindServer   Kind = "SERVER"
	KindProducer Kind = "PRODUCER"
	KindConsumer Kind = "CONSUMER"
)

// Endpoint is the network endpoint of a span (localEndpoint / remoteEndpoint).
// Zipkin lower-cases ServiceName on ingest.
type Endpoint struct {
	ServiceName string `json:"serviceName,omitempty"`
	IPv4        string `json:"ipv4,omitempty"`
	IPv6        string `json:"ipv6,omitempty"`
	Port        int    `json:"port,omitempty"`
}

// Annotation is a timestamped event on a span.
type Annotation struct {
	// Timestamp is the event time in epoch microseconds.
	Timestamp int64 `json:"timestamp"`
	// Value is the event text.
	Value string `json:"value"`
}

// ZipkinSpan is a Zipkin v2 span exactly as the server stores and returns it (the
// JSON field names are the wire names; empty fields are omitted). Ids are lower-case
// hex: TraceID 16 or 32 characters, ID and ParentID 16. Times are epoch microseconds,
// durations microseconds. Zipkin lower-cases Name and the endpoint service names on
// ingest.
//
//nolint:revive // the name matches the other languages; Span is the SPEC trace model.
type ZipkinSpan struct {
	TraceID        string            `json:"traceId"`
	ParentID       string            `json:"parentId,omitempty"`
	ID             string            `json:"id"`
	Kind           Kind              `json:"kind,omitempty"`
	Name           string            `json:"name,omitempty"`
	Timestamp      int64             `json:"timestamp,omitempty"`
	Duration       int64             `json:"duration,omitempty"`
	LocalEndpoint  *Endpoint         `json:"localEndpoint,omitempty"`
	RemoteEndpoint *Endpoint         `json:"remoteEndpoint,omitempty"`
	Annotations    []Annotation      `json:"annotations,omitempty"`
	Tags           map[string]string `json:"tags,omitempty"`
	// Debug forces the span to be stored (B3 debug flag).
	Debug bool `json:"debug,omitempty"`
	// Shared marks a server span that shares its id with the client span.
	Shared bool `json:"shared,omitempty"`
}

// ZipkinDependencyLink is one edge of Zipkin's dependency graph, with Zipkin's error
// count (0 when the server omits it).
//
//nolint:revive // the name matches the other languages; DependencyLink is the SPEC model.
type ZipkinDependencyLink struct {
	Parent     string `json:"parent"`
	Child      string `json:"child"`
	CallCount  int64  `json:"callCount"`
	ErrorCount int64  `json:"errorCount"`
}

// Link converts to the SPEC DependencyLink (without the error count).
func (l ZipkinDependencyLink) Link() DependencyLink {
	return DependencyLink{Parent: l.Parent, Child: l.Child, CallCount: l.CallCount}
}

// isLowerHex reports whether s has exactly n lower-case hex characters and is not all zeros.
func isLowerHex(s string, sizes ...int) bool {
	ok := false
	for _, n := range sizes {
		if len(s) == n {
			ok = true
		}
	}
	if !ok {
		return false
	}
	zero := true
	for _, c := range s {
		switch {
		case c == '0':
		case (c >= '1' && c <= '9') || (c >= 'a' && c <= 'f'):
			zero = false
		default:
			return false
		}
	}
	return !zero
}

// ValidateTraceID lower-cases and validates a Zipkin trace id (16 or 32 hex characters,
// not all zeros). Anything else is a VALIDATION error, raised before any request.
func ValidateTraceID(id, operation string) (string, error) {
	s := strings.ToLower(strings.TrimSpace(id))
	if !isLowerHex(s, 16, 32) {
		return "", ValidationError(operation, "trace id must be 16 or 32 hex characters")
	}
	return s, nil
}

func zipkinSpanID(id, what, operation string) (string, error) {
	s := strings.ToLower(strings.TrimSpace(id))
	if !isLowerHex(s, 16) {
		return "", ValidationError(operation, what+" must be 16 hex characters")
	}
	return s, nil
}

// TagValue converts a value to Zipkin's string tag form: strings as is, booleans
// "true"/"false", numbers in decimal ("7", "1.5"), nil as "", anything else
// (slices, maps) JSON-encoded.
func TagValue(v any) string {
	switch x := v.(type) {
	case nil:
		return ""
	case string:
		return x
	case bool:
		return strconv.FormatBool(x)
	case int:
		return strconv.Itoa(x)
	case int64:
		return strconv.FormatInt(x, 10)
	case int32:
		return strconv.FormatInt(int64(x), 10)
	case uint64:
		return strconv.FormatUint(x, 10)
	case float64:
		return strconv.FormatFloat(x, 'f', -1, 64)
	case float32:
		return strconv.FormatFloat(float64(x), 'f', -1, 32)
	case fmt.Stringer:
		return x.String()
	}
	raw, err := json.Marshal(v)
	if err != nil {
		return fmt.Sprint(v)
	}
	return string(raw)
}

// StringTags converts attribute-like values to Zipkin string tags with TagValue.
func StringTags(m map[string]any) map[string]string {
	out := make(map[string]string, len(m))
	for k, v := range m {
		out[k] = TagValue(v)
	}
	return out
}

func normalizeEndpoint(e *Endpoint, operation string) (*Endpoint, error) {
	if e == nil {
		return nil, nil
	}
	if e.Port < 0 || e.Port > 65535 {
		return nil, ValidationError(operation, "endpoint port must be 0..65535")
	}
	if *e == (Endpoint{}) {
		return nil, nil
	}
	out := *e
	return &out, nil
}

// Normalize applies the rules Zipkin enforces on ingest locally, so one bad span never
// makes the server reject a whole batch: ids are lower-cased and validated, Kind is
// upper-cased and checked, negative times are rejected, empty endpoints, annotations
// and tags are dropped. It returns a VALIDATION error naming the problem.
func (s ZipkinSpan) Normalize() (ZipkinSpan, error) {
	const op = "zipkin.report"
	out := s
	var err error
	if out.TraceID, err = ValidateTraceID(s.TraceID, op); err != nil {
		return ZipkinSpan{}, err
	}
	if out.ID, err = zipkinSpanID(s.ID, "span id", op); err != nil {
		return ZipkinSpan{}, err
	}
	if strings.TrimSpace(s.ParentID) != "" {
		if out.ParentID, err = zipkinSpanID(s.ParentID, "parentId", op); err != nil {
			return ZipkinSpan{}, err
		}
	} else {
		out.ParentID = ""
	}
	if s.Kind != "" {
		out.Kind = Kind(strings.ToUpper(string(s.Kind)))
		switch out.Kind {
		case KindClient, KindServer, KindProducer, KindConsumer:
		default:
			return ZipkinSpan{}, ValidationError(op, "kind must be CLIENT, SERVER, PRODUCER or CONSUMER")
		}
	}
	if s.Timestamp < 0 || s.Duration < 0 {
		return ZipkinSpan{}, ValidationError(op, "timestamp and duration must not be negative")
	}
	if out.LocalEndpoint, err = normalizeEndpoint(s.LocalEndpoint, op); err != nil {
		return ZipkinSpan{}, err
	}
	if out.RemoteEndpoint, err = normalizeEndpoint(s.RemoteEndpoint, op); err != nil {
		return ZipkinSpan{}, err
	}
	out.Annotations = nil
	for _, a := range s.Annotations {
		if a.Value == "" {
			return ZipkinSpan{}, ValidationError(op, "annotation value must not be empty")
		}
		if a.Timestamp <= 0 {
			return ZipkinSpan{}, ValidationError(op, "annotation timestamp is required")
		}
		out.Annotations = append(out.Annotations, a)
	}
	out.Tags = nil
	if len(s.Tags) > 0 {
		out.Tags = make(map[string]string, len(s.Tags))
		for k, v := range s.Tags {
			out.Tags[k] = v
		}
	}
	return out, nil
}

// AnnotationToEvent decodes an annotation into a SPEC span event: a value of the form
// {"<name>": {...attributes}} (how the SpanExporter encodes an event with attributes)
// becomes that name and attributes; any other value is the event name.
func AnnotationToEvent(a Annotation) SpanEvent {
	v := a.Value
	if strings.HasPrefix(v, "{") && strings.HasSuffix(v, "}") {
		dec := json.NewDecoder(bytes.NewReader([]byte(v)))
		dec.UseNumber()
		var parsed map[string]any
		if dec.Decode(&parsed) == nil && len(parsed) == 1 {
			for name, raw := range parsed {
				if attrs, ok := raw.(map[string]any); ok {
					return SpanEvent{Name: name, TimeMicros: a.Timestamp, Attributes: numbersToGo(attrs)}
				}
			}
		}
	}
	return SpanEvent{Name: v, TimeMicros: a.Timestamp, Attributes: map[string]any{}}
}

func zipkinKindToSpanKind(k Kind) SpanKind {
	switch k {
	case "":
		return SpanKindInternal
	case KindClient:
		return SpanKindClient
	case KindServer:
		return SpanKindServer
	case KindProducer:
		return SpanKindProducer
	case KindConsumer:
		return SpanKindConsumer
	}
	return SpanKindUnspecified
}

// ToSpan converts the span to the SPEC trace model: trace id padded to 32 characters,
// kind lower-cased (empty → "internal"), ServiceName = localEndpoint.serviceName,
// attributes = tags, annotations → events (AnnotationToEvent), status from the "error"
// tag (error, message = its value) or "otel.status_code" (OK / ERROR), no links.
// Endpoints, Shared and Debug are only in ZipkinSpan.
func (s ZipkinSpan) ToSpan() Span {
	traceID, err := NormalizeTraceID(s.TraceID)
	if err != nil {
		traceID = strings.ToLower(s.TraceID)
	}
	out := Span{
		TraceID:        traceID,
		SpanID:         strings.ToLower(s.ID),
		ParentSpanID:   strings.ToLower(s.ParentID),
		Name:           s.Name,
		Kind:           zipkinKindToSpanKind(s.Kind),
		StartMicros:    s.Timestamp,
		EndMicros:      s.Timestamp + s.Duration,
		DurationMicros: s.Duration,
		Status:         SpanStatus{Code: SpanStatusUnset},
		Attributes:     make(map[string]any, len(s.Tags)),
		Events:         make([]SpanEvent, 0, len(s.Annotations)),
		Links:          []SpanLink{},
	}
	if s.LocalEndpoint != nil {
		out.ServiceName = s.LocalEndpoint.ServiceName
	}
	for k, v := range s.Tags {
		out.Attributes[k] = v
	}
	if msg, ok := s.Tags["error"]; ok {
		out.Status = SpanStatus{Code: SpanStatusError, Message: msg}
	} else {
		switch strings.ToUpper(s.Tags["otel.status_code"]) {
		case "ERROR":
			out.Status.Code = SpanStatusError
		case "OK":
			out.Status.Code = SpanStatusOK
		}
	}
	for _, a := range s.Annotations {
		out.Events = append(out.Events, AnnotationToEvent(a))
	}
	return out
}

// TraceFromZipkin converts the spans of one Zipkin trace to a Trace (spans sorted by
// start time, then span id). It returns nil for an empty slice.
func TraceFromZipkin(spans []ZipkinSpan) *Trace {
	if len(spans) == 0 {
		return nil
	}
	out := make([]Span, len(spans))
	for i, s := range spans {
		out[i] = s.ToSpan()
	}
	SortSpans(out)
	return &Trace{TraceID: out[0].TraceID, Spans: out}
}

// SummarizeZipkinTrace summarises one Zipkin trace: the root is the span without a
// parent in the trace (else the earliest span), StartMicros the earliest timestamp,
// DurationMicros the latest end minus the earliest start. ok is false for an empty slice.
func SummarizeZipkinTrace(spans []ZipkinSpan) (summary TraceSummary, ok bool) {
	t := TraceFromZipkin(spans)
	if t == nil {
		return TraceSummary{}, false
	}
	ids := make(map[string]bool, len(t.Spans))
	for _, s := range t.Spans {
		ids[s.SpanID] = true
	}
	root := t.Spans[0]
	for _, s := range t.Spans {
		if s.ParentSpanID == "" || !ids[s.ParentSpanID] {
			root = s
			break
		}
	}
	start, end := t.Spans[0].StartMicros, t.Spans[0].EndMicros
	for _, s := range t.Spans {
		start = min(start, s.StartMicros)
		end = max(end, s.EndMicros)
	}
	return TraceSummary{
		TraceID:         t.TraceID,
		RootServiceName: root.ServiceName,
		RootName:        root.Name,
		StartMicros:     start,
		DurationMicros:  end - start,
		SpanCount:       len(t.Spans),
	}, true
}

// sortDependencies sorts links by parent, then child.
func sortDependencies(links []ZipkinDependencyLink) {
	sort.SliceStable(links, func(i, j int) bool {
		if links[i].Parent != links[j].Parent {
			return links[i].Parent < links[j].Parent
		}
		return links[i].Child < links[j].Child
	})
}
