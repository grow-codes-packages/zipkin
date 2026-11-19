package zipkin

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"
)

// DefaultSearchWindow is the SearchTraces window when TraceSearchQuery.Start is unset.
const DefaultSearchWindow = time.Hour

// TraceManyChunk is the number of ids per /api/v2/traceMany request (URL length).
const TraceManyChunk = 100

// QueryClient reads traces through the Zipkin v2 query API (GET /api/v2/...). Get it
// from Client.Query. It implements TraceQueryClient (normalized model) and adds the
// Zipkin-native calls that return ZipkinSpan values. It is safe for concurrent use.
type QueryClient struct {
	transport *httpTransport
}

var _ TraceQueryClient = (*QueryClient)(nil)

// TracesQuery are the filters of QueryClient.GetTraces (GET /api/v2/traces). Zero values
// mean "not set" (server defaults: EndTs now, Lookback 24 h, Limit 10).
type TracesQuery struct {
	// ServiceName matches localEndpoint.serviceName (case-insensitive).
	ServiceName string
	// RemoteServiceName matches remoteEndpoint.serviceName.
	RemoteServiceName string
	// SpanName matches the span name.
	SpanName string
	// AnnotationQuery is Zipkin's raw tag/annotation filter: "key=value" matches a tag
	// exactly, a bare "key" matches a tag key or an annotation value, terms are joined
	// with " and " (AND only).
	AnnotationQuery string
	// Tags are exact tag matches (key=value), combined with AnnotationQuery by " and ".
	// A key or value that contains " and " (or a key that contains "=") cannot be
	// expressed in Zipkin's syntax: that term is matched client-side after the query,
	// so fewer than Limit traces may come back.
	Tags map[string]string
	// Annotations are bare terms (a tag key or an annotation value such as "error" or
	// "cache.miss"), handled like Tags.
	Annotations []string
	// MinDuration and MaxDuration bound the span duration (microsecond precision);
	// MaxDuration needs MinDuration.
	MinDuration time.Duration
	MaxDuration time.Duration
	// EndTs is the end of the time window (default now).
	EndTs time.Time
	// Lookback is the window length before EndTs (millisecond precision).
	Lookback time.Duration
	// Limit is the maximum number of traces (server default 10).
	Limit int
}

func (q *QueryClient) get(ctx context.Context, operation, path string, params url.Values) (*response, error) {
	return q.transport.do(ctx, &request{Method: http.MethodGet, Path: path, Query: params, Operation: operation})
}

func (q *QueryClient) getJSON(ctx context.Context, operation, path string, params url.Values, out any) error {
	res, err := q.get(ctx, operation, path, params)
	if err != nil {
		return err
	}
	return res.decodeJSON(operation, out)
}

// strings decodes a string list; sorted unless keepOrder.
func (q *QueryClient) strings(ctx context.Context, operation, path string, params url.Values, keepOrder bool) ([]string, error) {
	var out []string
	if err := q.getJSON(ctx, operation, path, params, &out); err != nil {
		return nil, err
	}
	if out == nil {
		out = []string{}
	}
	if !keepOrder {
		sort.Strings(out)
	}
	return out, nil
}

func requireName(operation, what, value string) error {
	if strings.TrimSpace(value) == "" {
		return ValidationError(operation, what+" is required")
	}
	return nil
}

// GetTraceSpans returns the spans of one trace as stored (GET /api/v2/trace/{id}), or nil
// when the trace does not exist. The id must be 16 or 32 hex characters (it is
// lower-cased; anything else is a VALIDATION error without a request).
func (q *QueryClient) GetTraceSpans(ctx context.Context, traceID string) ([]ZipkinSpan, error) {
	const op = "zipkin.getTrace"
	id, err := ValidateTraceID(traceID, op)
	if err != nil {
		return nil, err
	}
	var spans []ZipkinSpan
	if err := q.getJSON(ctx, op, "/api/v2/trace/"+id, nil, &spans); err != nil {
		if errors.Is(err, ErrNotFound) {
			return nil, nil
		}
		return nil, err
	}
	if len(spans) == 0 {
		return nil, nil
	}
	return spans, nil
}

// GetTrace returns one trace in the normalized model (ZipkinSpan.ToSpan; 64-bit trace ids
// are left-padded to 32 characters), or nil when it does not exist. The id rules are
// those of GetTraceSpans.
func (q *QueryClient) GetTrace(ctx context.Context, traceID string) (*Trace, error) {
	spans, err := q.GetTraceSpans(ctx, traceID)
	if err != nil {
		return nil, err
	}
	return TraceFromZipkin(spans), nil
}

// params validates the query locally (the same rules the server applies) and encodes it.
func (t TracesQuery) params(operation string) (url.Values, error) {
	switch {
	case t.MinDuration < 0 || t.MaxDuration < 0:
		return nil, ValidationError(operation, "minDuration and maxDuration must not be negative")
	case t.MaxDuration > 0 && t.MinDuration == 0:
		return nil, ValidationError(operation, "maxDuration is only valid with minDuration")
	case t.MaxDuration > 0 && t.MaxDuration < t.MinDuration:
		return nil, ValidationError(operation, "maxDuration must not be less than minDuration")
	case t.Lookback < 0:
		return nil, ValidationError(operation, "lookback must be positive")
	case t.Limit < 0:
		return nil, ValidationError(operation, "limit must be positive")
	case !t.EndTs.IsZero() && t.EndTs.UnixMilli() <= 0:
		return nil, ValidationError(operation, "endTs must be after the Unix epoch")
	}
	p := url.Values{}
	set := func(k, v string) {
		if v != "" {
			p.Set(k, v)
		}
	}
	set("serviceName", t.ServiceName)
	set("remoteServiceName", t.RemoteServiceName)
	set("spanName", t.SpanName)
	server, _, err := t.terms(operation)
	if err != nil {
		return nil, err
	}
	set("annotationQuery", strings.Join(server, " and "))
	if t.MinDuration > 0 {
		p.Set("minDuration", strconv.FormatInt(t.MinDuration.Microseconds(), 10))
	}
	if t.MaxDuration > 0 {
		p.Set("maxDuration", strconv.FormatInt(t.MaxDuration.Microseconds(), 10))
	}
	if !t.EndTs.IsZero() {
		p.Set("endTs", strconv.FormatInt(t.EndTs.UnixMilli(), 10))
	}
	if t.Lookback > 0 {
		p.Set("lookback", strconv.FormatInt(max(t.Lookback.Milliseconds(), 1), 10))
	}
	if t.Limit > 0 {
		p.Set("limit", strconv.Itoa(t.Limit))
	}
	return p, nil
}

// term is one tag (Value set) or bare annotation term.
type term struct {
	key, value string
	bare       bool
}

func (t term) serverSide() bool {
	return !strings.Contains(t.key, " and ") && !strings.Contains(t.key, "=") && !strings.Contains(t.value, " and ")
}

func (t term) text() string {
	if t.bare {
		return t.key
	}
	return t.key + "=" + t.value
}

// matches reports whether some span of the trace has the tag (or, for a bare term, the
// tag key or an annotation with that value).
func (t term) matches(spans []ZipkinSpan) bool {
	for _, s := range spans {
		if v, ok := s.Tags[t.key]; ok && (t.bare || v == t.value) {
			return true
		}
		if t.bare {
			for _, a := range s.Annotations {
				if a.Value == t.key {
					return true
				}
			}
		}
	}
	return false
}

// terms splits the annotation query into the parts sent to Zipkin and the terms matched
// client-side.
func (t TracesQuery) terms(operation string) (server []string, local []term, err error) {
	if raw := strings.TrimSpace(t.AnnotationQuery); raw != "" {
		server = append(server, raw)
	}
	keys := make([]string, 0, len(t.Tags))
	for k := range t.Tags {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	all := make([]term, 0, len(keys)+len(t.Annotations))
	for _, k := range keys {
		all = append(all, term{key: k, value: t.Tags[k]})
	}
	for _, a := range t.Annotations {
		all = append(all, term{key: a, bare: true})
	}
	for _, tm := range all {
		if tm.key == "" {
			return nil, nil, ValidationError(operation, "tag keys and annotations must not be empty")
		}
		if tm.serverSide() {
			server = append(server, tm.text())
		} else {
			local = append(local, tm)
		}
	}
	return server, local, nil
}

// GetTraces searches traces (GET /api/v2/traces) and returns each matching trace as its
// stored spans, newest first. No match is an empty slice. Invalid filters are
// VALIDATION errors without a request.
func (q *QueryClient) GetTraces(ctx context.Context, query TracesQuery) ([][]ZipkinSpan, error) {
	const op = "zipkin.getTraces"
	p, err := query.params(op)
	if err != nil {
		return nil, err
	}
	_, local, _ := query.terms(op)
	var traces [][]ZipkinSpan
	if err := q.getJSON(ctx, op, "/api/v2/traces", p, &traces); err != nil {
		return nil, err
	}
	out := make([][]ZipkinSpan, 0, len(traces))
	for _, t := range nonEmptyTraces(traces) {
		keep := true
		for _, tm := range local {
			if !tm.matches(t) {
				keep = false
				break
			}
		}
		if keep {
			out = append(out, t)
		}
	}
	return out, nil
}

func nonEmptyTraces(traces [][]ZipkinSpan) [][]ZipkinSpan {
	out := make([][]ZipkinSpan, 0, len(traces))
	for _, t := range traces {
		if len(t) > 0 {
			out = append(out, t)
		}
	}
	return out
}

// paddedKey is the trace id key used to match traces (64-bit ids padded to 32).
func paddedKey(id string) string {
	return strings.Repeat("0", max(0, 32-len(id))) + strings.ToLower(id)
}

// GetTraceMany returns several traces (GET /api/v2/traceMany). No ids return an empty
// slice without a request; ids are lower-cased and de-duplicated (a 64-bit id and its
// zero-padded 32-character form count as one); missing traces are omitted and the
// result follows the order of the request.
func (q *QueryClient) GetTraceMany(ctx context.Context, traceIDs []string) ([][]ZipkinSpan, error) {
	const op = "zipkin.getTraceMany"
	ids := make([]string, 0, len(traceIDs))
	seen := make(map[string]bool, len(traceIDs))
	for _, raw := range traceIDs {
		id, err := ValidateTraceID(raw, op)
		if err != nil {
			return nil, err
		}
		if key := paddedKey(id); !seen[key] {
			seen[key] = true
			ids = append(ids, id)
		}
	}
	switch len(ids) {
	case 0:
		return [][]ZipkinSpan{}, nil
	case 1:
		spans, err := q.GetTraceSpans(ctx, ids[0])
		if err != nil || spans == nil {
			return [][]ZipkinSpan{}, err
		}
		return [][]ZipkinSpan{spans}, nil
	}
	found := make(map[string][]ZipkinSpan, len(ids))
	for start := 0; start < len(ids); start += TraceManyChunk {
		chunk := ids[start:min(start+TraceManyChunk, len(ids))]
		var traces [][]ZipkinSpan
		if len(chunk) == 1 {
			spans, err := q.GetTraceSpans(ctx, chunk[0])
			if err != nil {
				return nil, err
			}
			traces = [][]ZipkinSpan{spans}
		} else {
			p := url.Values{"traceIds": {strings.Join(chunk, ",")}}
			if err := q.getJSON(ctx, op, "/api/v2/traceMany", p, &traces); err != nil {
				return nil, err
			}
		}
		for _, t := range nonEmptyTraces(traces) {
			found[paddedKey(t[0].TraceID)] = t
		}
	}
	out := make([][]ZipkinSpan, 0, len(found))
	for _, id := range ids {
		if t, ok := found[paddedKey(id)]; ok {
			out = append(out, t)
		}
	}
	return out, nil
}

// ListServices returns the service names seen in spans (GET /api/v2/services), sorted
// and lower-case.
func (q *QueryClient) ListServices(ctx context.Context) ([]string, error) {
	return q.strings(ctx, "zipkin.listServices", "/api/v2/services", nil, false)
}

// ListSpans returns the span names of a service (GET /api/v2/spans?serviceName=), sorted.
// An unknown service returns an empty slice.
func (q *QueryClient) ListSpans(ctx context.Context, serviceName string) ([]string, error) {
	const op = "zipkin.listSpans"
	if err := requireName(op, "serviceName", serviceName); err != nil {
		return nil, err
	}
	return q.strings(ctx, op, "/api/v2/spans", url.Values{"serviceName": {serviceName}}, false)
}

// ListOperations is ListSpans (TraceQueryClient).
func (q *QueryClient) ListOperations(ctx context.Context, service string) ([]string, error) {
	return q.ListSpans(ctx, service)
}

// ListRemoteServices returns the remote service names seen on a service's spans
// (GET /api/v2/remoteServices?serviceName=).
func (q *QueryClient) ListRemoteServices(ctx context.Context, serviceName string) ([]string, error) {
	const op = "zipkin.listRemoteServices"
	if err := requireName(op, "serviceName", serviceName); err != nil {
		return nil, err
	}
	return q.strings(ctx, op, "/api/v2/remoteServices", url.Values{"serviceName": {serviceName}}, false)
}

// GetDependenciesAt returns the service dependency links of the window
// [endTs-lookback, endTs] (GET /api/v2/dependencies), sorted by parent then child. A zero
// endTs means now, a zero lookback the server default (24 h). ErrorCount is 0 when
// Zipkin omits it.
func (q *QueryClient) GetDependenciesAt(ctx context.Context, endTs time.Time, lookback time.Duration) ([]ZipkinDependencyLink, error) {
	const op = "zipkin.getDependencies"
	if lookback < 0 {
		return nil, ValidationError(op, "lookback must be positive")
	}
	if endTs.IsZero() {
		endTs = time.Now()
	}
	if endTs.UnixMilli() <= 0 {
		return nil, ValidationError(op, "endTs must be after the Unix epoch")
	}
	p := url.Values{"endTs": {strconv.FormatInt(endTs.UnixMilli(), 10)}}
	if lookback > 0 {
		p.Set("lookback", strconv.FormatInt(max(lookback.Milliseconds(), 1), 10))
	}
	var links []ZipkinDependencyLink
	if err := q.getJSON(ctx, op, "/api/v2/dependencies", p, &links); err != nil {
		return nil, err
	}
	if links == nil {
		links = []ZipkinDependencyLink{}
	}
	sortDependencies(links)
	return links, nil
}

// window converts a Start/End range to Zipkin's endTs + lookback.
func window(operation string, start, end time.Time) (time.Time, time.Duration, error) {
	if end.IsZero() {
		end = time.Now()
	}
	if start.IsZero() {
		return end, 0, nil
	}
	if !start.Before(end) {
		return time.Time{}, 0, ValidationError(operation, "lookback must be a positive duration (start before end)")
	}
	return end, end.Sub(start), nil
}

// GetDependencies returns the dependency links of the range (TraceQueryClient): End
// defaults to now, no Start means Zipkin's default lookback (24 h).
func (q *QueryClient) GetDependencies(ctx context.Context, opts DependencyOptions) ([]DependencyLink, error) {
	end, lookback, err := window("zipkin.getDependencies", opts.Start, opts.End)
	if err != nil {
		return nil, err
	}
	links, err := q.GetDependenciesAt(ctx, end, lookback)
	if err != nil {
		return nil, err
	}
	out := make([]DependencyLink, len(links))
	for i, l := range links {
		out[i] = l.Link()
	}
	return out, nil
}

// AutocompleteKeys returns the tag keys the server indexes for autocompletion (its
// AUTOCOMPLETE_KEYS setting; empty when unset).
func (q *QueryClient) AutocompleteKeys(ctx context.Context) ([]string, error) {
	return q.strings(ctx, "zipkin.autocompleteKeys", "/api/v2/autocompleteKeys", nil, true)
}

// AutocompleteValues returns the distinct values seen for a tag key, sorted (empty when
// the key is not in the server's AUTOCOMPLETE_KEYS).
func (q *QueryClient) AutocompleteValues(ctx context.Context, key string) ([]string, error) {
	const op = "zipkin.autocompleteValues"
	if err := requireName(op, "key", key); err != nil {
		return nil, err
	}
	return q.strings(ctx, op, "/api/v2/autocompleteValues", url.Values{"key": {key}}, false)
}

// SearchTraces searches traces and summarises them (TraceQueryClient): Service →
// serviceName, Operation → spanName, Tags → exact tag terms (see TracesQuery.Tags; terms
// Zipkin cannot express are matched client-side), durations, End → endTs (default now),
// Start → lookback = End − Start (at least 1 ms; DefaultSearchWindow = 1 h without Start),
// Limit (default 20). The root of a summary is the span without a parent, else the
// earliest span.
func (q *QueryClient) SearchTraces(ctx context.Context, query TraceSearchQuery) ([]TraceSummary, error) {
	const op = "zipkin.searchTraces"
	query = query.WithDefaults()
	end := query.End
	if end.IsZero() {
		end = time.Now()
	}
	lookback := DefaultSearchWindow
	if !query.Start.IsZero() {
		if query.Start.After(end) {
			return nil, ValidationError(op, "start must not be after end")
		}
		lookback = max(end.Sub(query.Start), time.Millisecond)
	}
	traces, err := q.GetTraces(ctx, TracesQuery{
		ServiceName: query.Service,
		SpanName:    query.Operation,
		Tags:        query.Tags,
		MinDuration: query.MinDuration,
		MaxDuration: query.MaxDuration,
		EndTs:       end,
		Lookback:    lookback,
		Limit:       query.Limit,
	})
	if err != nil {
		return nil, err
	}
	out := make([]TraceSummary, 0, len(traces))
	for _, t := range traces {
		if s, ok := SummarizeZipkinTrace(t); ok {
			out = append(out, s)
		}
	}
	return out, nil
}

// WaitForTrace polls GetTrace until the trace has at least MinSpans spans (test helper).
func (q *QueryClient) WaitForTrace(ctx context.Context, traceID string, opts WaitForTraceOptions) (*Trace, error) {
	return WaitForTrace(ctx, q, traceID, opts)
}
