package zipkin

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"
)

// fixtureEnd is the endTs of the recorded window queries (epoch ms 1795035940000).
var fixtureEnd = time.UnixMilli(1795035940000)

func queryClient(t *testing.T, names ...string) (*QueryClient, *testServer) {
	t.Helper()
	ts := fixtureServer(t, names...)
	return newTestClient(t, ts.URL, nil).Query(), ts
}

func TestGetTrace(t *testing.T) {
	q, ts := queryClient(t, "getTrace.ok", "getTrace.notFound", "getTrace.id64", "getTrace.invalid400")

	spans, err := q.GetTraceSpans(t.Context(), "5AF7183FB1D4CF5FAD1C0DF61D0A9B8C")
	if err != nil || len(spans) != 3 || spans[2].Tags["error"] != "boom" {
		t.Fatalf("spans %v %v", spans, err)
	}
	missing, err := q.GetTraceSpans(t.Context(), "ffffffffffffffffffffffffffffffff")
	if err != nil || missing != nil {
		t.Fatalf("missing %v %v", missing, err)
	}
	if _, err := q.GetTraceSpans(t.Context(), "zz"); !errors.Is(err, ErrValidation) {
		t.Fatalf("invalid: %v", err)
	}
	before := ts.calls.Load()
	for _, bad := range []string{"", "123", "0000000000000000", strings.Repeat("g", 16)} {
		if _, err := q.GetTraceSpans(t.Context(), bad); !errors.Is(err, ErrValidation) {
			t.Errorf("%q: %v", bad, err)
		}
	}
	if ts.calls.Load() != before {
		t.Fatal("invalid ids must not be sent")
	}

	tr, err := q.GetTrace(t.Context(), "5af7183fb1d4cf5fad1c0df61d0a9b8c")
	if err != nil || tr == nil || len(tr.Spans) != 3 || tr.Spans[0].Name != "get /api/orders" {
		t.Fatalf("trace %+v %v", tr, err)
	}
	if tr, err := q.GetTrace(t.Context(), "ffffffffffffffffffffffffffffffff"); err != nil || tr != nil {
		t.Fatalf("missing trace %+v %v", tr, err)
	}
	if _, err := q.GetTrace(t.Context(), "abc"); !errors.Is(err, ErrValidation) {
		t.Fatalf("invalid trace id: %v", err)
	}
	// 64-bit ids are sent as given and padded to 32 characters in the normalized model.
	padded := newTestServer(t, func(w http.ResponseWriter, r *http.Request, _ int) {
		if r.URL.Path != "/api/v2/trace/463ac35cad1c0df6" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		loadFixture(t, "getTrace.id64").write(w)
	})
	tr, err = newTestClient(t, padded.URL, nil).Query().GetTrace(t.Context(), "463ac35cad1c0df6")
	if err != nil || tr == nil || tr.TraceID != "0000000000000000463ac35cad1c0df6" {
		t.Fatalf("id64 %+v %v", tr, err)
	}
	// An empty array is "not found"; a server error is returned.
	empty := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) { _, _ = w.Write([]byte(`[]`)) })
	if s, err := newTestClient(t, empty.URL, nil).Query().GetTraceSpans(t.Context(), "463ac35cad1c0df6"); err != nil || s != nil {
		t.Fatalf("empty %v %v", s, err)
	}
	broken := newTestServer(t, status(http.StatusBadGateway))
	bq := newTestClient(t, broken.URL, func(c *Config) { c.Retry = fastRetry(0) }).Query()
	if _, err := bq.GetTrace(t.Context(), "463ac35cad1c0df6"); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("502: %v", err)
	}
}

func TestGetTraces(t *testing.T) {
	q, _ := queryClient(t, "getTraces.allFilters", "getTraces.defaults", "getTraces.limit1",
		"getTraces.remoteServiceName", "getTraces.minDurationExcludes", "getTraces.windowExcludes")

	traces, err := q.GetTraces(t.Context(), TracesQuery{
		ServiceName:     "grow-frontend",
		SpanName:        "get /api/orders",
		AnnotationQuery: "http.method=GET and cache.miss",
		MinDuration:     100 * time.Millisecond,
		MaxDuration:     200 * time.Millisecond,
		EndTs:           fixtureEnd,
		Lookback:        time.Hour,
		Limit:           5,
	})
	if err != nil || len(traces) != 1 || len(traces[0]) != 3 {
		t.Fatalf("all filters %v %v", traces, err)
	}
	if all, err := q.GetTraces(t.Context(), TracesQuery{}); err != nil || len(all) != 8 {
		t.Fatalf("defaults %d %v", len(all), err)
	}
	if one, err := q.GetTraces(t.Context(), TracesQuery{Limit: 1}); err != nil || len(one) != 1 {
		t.Fatalf("limit %v %v", one, err)
	}
	if r, err := q.GetTraces(t.Context(), TracesQuery{RemoteServiceName: "grow-backend"}); err != nil || len(r) != 1 {
		t.Fatalf("remote %v %v", r, err)
	}
	if r, err := q.GetTraces(t.Context(), TracesQuery{ServiceName: "grow-frontend", MinDuration: 900 * time.Millisecond}); err != nil || r == nil || len(r) != 0 {
		t.Fatalf("min duration %v %v", r, err)
	}
	if r, err := q.GetTraces(t.Context(), TracesQuery{ServiceName: "grow-frontend", EndTs: time.UnixMilli(1000), Lookback: time.Second}); err != nil || len(r) != 0 {
		t.Fatalf("window %v %v", r, err)
	}

	bad := []TracesQuery{
		{MinDuration: -1},
		{MaxDuration: 10 * time.Microsecond},
		{MinDuration: 10 * time.Microsecond, MaxDuration: 5 * time.Microsecond},
		{Lookback: -time.Second},
		{Limit: -1},
		{EndTs: time.UnixMilli(-5)},
	}
	for i, b := range bad {
		if _, err := q.GetTraces(t.Context(), b); !errors.Is(err, ErrValidation) {
			t.Errorf("case %d: %v", i, err)
		}
	}
	// Server-side validation text is kept in the error.
	srv, _ := queryClient(t, "getTraces.maxWithoutMin400")
	err = srv.getJSON(t.Context(), "zipkin.getTraces", "/api/v2/traces", url.Values{"maxDuration": {"10"}}, &traces)
	if !errors.Is(err, ErrValidation) || !strings.Contains(err.Error(), "maxDuration is only valid with minDuration") {
		t.Fatalf("server 400: %v", err)
	}
	broken := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) { _, _ = w.Write([]byte(`{`)) })
	if _, err := newTestClient(t, broken.URL, nil).Query().GetTraces(t.Context(), TracesQuery{}); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("bad json: %v", err)
	}
}

func TestGetTraceMany(t *testing.T) {
	q, ts := queryClient(t, "getTraceMany.ok", "getTraceMany.allMissing", "getTrace.ok", "getTrace.notFound", "getTrace.id64")

	// Missing ids are omitted, duplicates removed, ids lower-cased (order: TestGetTraceManyChunks).
	traces, err := q.GetTraceMany(t.Context(), []string{
		"5af7183fb1d4cf5fad1c0df61d0a9b8c", "463ac35cad1c0df6", "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF", "5af7183fb1d4cf5fad1c0df61d0a9b8c",
	})
	if err != nil || len(traces) != 2 || traces[0][0].TraceID != "5af7183fb1d4cf5fad1c0df61d0a9b8c" || traces[1][0].TraceID != "463ac35cad1c0df6" {
		t.Fatalf("ok %v %v", traces, err)
	}
	if none, err := q.GetTraceMany(t.Context(), []string{"ffffffffffffffffffffffffffffffff", "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"}); err != nil || none == nil || len(none) != 0 {
		t.Fatalf("all missing %v %v", none, err)
	}
	calls := ts.calls.Load()
	if empty, err := q.GetTraceMany(t.Context(), nil); err != nil || empty == nil || len(empty) != 0 || ts.calls.Load() != calls {
		t.Fatalf("empty %v %v", empty, err)
	}
	// One id (also after de-duplication) uses /api/v2/trace/{id}.
	one, err := q.GetTraceMany(t.Context(), []string{"5af7183fb1d4cf5fad1c0df61d0a9b8c", "5AF7183FB1D4CF5FAD1C0DF61D0A9B8C"})
	if err != nil || len(one) != 1 || len(one[0]) != 3 {
		t.Fatalf("single %v %v", one, err)
	}
	if id64, err := q.GetTraceMany(t.Context(), []string{"463ac35cad1c0df6", "0000000000000000463AC35CAD1C0DF6"}); err != nil || len(id64) != 1 {
		t.Fatalf("64-bit id and padded form %v %v", id64, err)
	}
	if gone, err := q.GetTraceMany(t.Context(), []string{"ffffffffffffffffffffffffffffffff"}); err != nil || len(gone) != 0 {
		t.Fatalf("single missing %v %v", gone, err)
	}
	if _, err := q.GetTraceMany(t.Context(), []string{"5af7183fb1d4cf5fad1c0df61d0a9b8c", "zz"}); !errors.Is(err, ErrValidation) {
		t.Fatalf("invalid: %v", err)
	}
}

func TestGetTraceManyChunks(t *testing.T) {
	// 201 ids → chunks of 100, 100 and a single id; reordered client-side.
	var ids []string
	for i := 1; i <= 2*TraceManyChunk+1; i++ {
		ids = append(ids, fmt.Sprintf("%016x", i))
	}
	srv := newTestServer(t, func(w http.ResponseWriter, r *http.Request, _ int) {
		var reqIDs []string
		if strings.HasPrefix(r.URL.Path, "/api/v2/trace/") {
			reqIDs = []string{strings.TrimPrefix(r.URL.Path, "/api/v2/trace/")}
		} else {
			reqIDs = strings.Split(r.URL.Query().Get("traceIds"), ",")
			if len(reqIDs) > TraceManyChunk {
				w.WriteHeader(http.StatusBadRequest)
				return
			}
		}
		var traces [][]ZipkinSpan
		for i := len(reqIDs) - 1; i >= 0; i-- { // reversed on purpose
			if reqIDs[i] == "000000000000000a" {
				continue // missing
			}
			traces = append(traces, []ZipkinSpan{{TraceID: reqIDs[i], ID: reqIDs[i]}})
		}
		if strings.HasPrefix(r.URL.Path, "/api/v2/trace/") {
			_ = json.NewEncoder(w).Encode(traces[0])
			return
		}
		_ = json.NewEncoder(w).Encode(traces)
	})
	got, err := newTestClient(t, srv.URL, nil).Query().GetTraceMany(t.Context(), ids)
	if err != nil || len(got) != 2*TraceManyChunk || srv.calls.Load() != 3 {
		t.Fatalf("len=%d calls=%d err=%v", len(got), srv.calls.Load(), err)
	}
	for i, tr := range got[:9] { // id 10 (0x0a) is missing
		if want := fmt.Sprintf("%016x", i+1); tr[0].TraceID != want {
			t.Fatalf("order at %d: %s != %s", i, tr[0].TraceID, want)
		}
	}
	failing := newTestServer(t, status(http.StatusBadRequest))
	fq := newTestClient(t, failing.URL, nil).Query()
	if _, err := fq.GetTraceMany(t.Context(), ids[:2]); !errors.Is(err, ErrValidation) {
		t.Fatalf("chunk error: %v", err)
	}
	if _, err := fq.GetTraceMany(t.Context(), ids); !errors.Is(err, ErrValidation) {
		t.Fatalf("chunk error: %v", err)
	}
	single := newTestServer(t, func(w http.ResponseWriter, r *http.Request, _ int) {
		if strings.HasPrefix(r.URL.Path, "/api/v2/trace/") {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		_, _ = w.Write([]byte(`[]`))
	})
	if _, err := newTestClient(t, single.URL, nil).Query().GetTraceMany(t.Context(), ids); !errors.Is(err, ErrValidation) {
		t.Fatalf("last single chunk error: %v", err)
	}
}

func TestNameLists(t *testing.T) {
	q, ts := queryClient(t, "listServices.ok", "listSpans.ok", "listSpans.unknownService", "listRemoteServices.ok",
		"autocompleteKeys.ok", "autocompleteValues.ok", "autocompleteValues.notAllowlisted")
	if s, err := q.ListServices(t.Context()); err != nil || len(s) != 9 || s[0] != "grow-backend" {
		t.Fatalf("services %v %v", s, err)
	}
	if s, err := q.ListSpans(t.Context(), "grow-frontend"); err != nil || len(s) != 2 || s[0] != "get /api/orders" {
		t.Fatalf("spans %v %v", s, err)
	}
	if s, err := q.ListOperations(t.Context(), "grow-frontend"); err != nil || len(s) != 2 {
		t.Fatalf("operations %v %v", s, err)
	}
	if s, err := q.ListSpans(t.Context(), "nope"); err != nil || s == nil || len(s) != 0 {
		t.Fatalf("unknown %v %v", s, err)
	}
	if s, err := q.ListRemoteServices(t.Context(), "grow-frontend"); err != nil || len(s) != 1 || s[0] != "grow-backend" {
		t.Fatalf("remote %v %v", s, err)
	}
	if s, err := q.AutocompleteKeys(t.Context()); err != nil || len(s) != 2 || s[1] != "http.method" {
		t.Fatalf("keys %v %v", s, err)
	}
	if s, err := q.AutocompleteValues(t.Context(), "grow.test"); err != nil || len(s) != 6 {
		t.Fatalf("values %v %v", s, err)
	}
	if s, err := q.AutocompleteValues(t.Context(), "not.indexed"); err != nil || len(s) != 0 {
		t.Fatalf("not allowlisted %v %v", s, err)
	}
	calls := ts.calls.Load()
	if _, err := q.ListSpans(t.Context(), " "); !errors.Is(err, ErrValidation) {
		t.Fatal(err)
	}
	if _, err := q.ListRemoteServices(t.Context(), ""); !errors.Is(err, ErrValidation) {
		t.Fatal(err)
	}
	if _, err := q.AutocompleteValues(t.Context(), ""); !errors.Is(err, ErrValidation) {
		t.Fatal(err)
	}
	if ts.calls.Load() != calls {
		t.Fatal("validation errors must not send")
	}
	nullBody := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) { _, _ = w.Write([]byte(`null`)) })
	if s, err := newTestClient(t, nullBody.URL, nil).Query().ListServices(t.Context()); err != nil || s == nil {
		t.Fatalf("null %v %v", s, err)
	}
	broken := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) { _, _ = w.Write([]byte(`{`)) })
	if _, err := newTestClient(t, broken.URL, nil).Query().ListServices(t.Context()); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("bad json: %v", err)
	}
}

func TestDependencies(t *testing.T) {
	q, _ := queryClient(t, "getDependencies.ok", "getDependencies.defaultLookback", "getDependencies.emptyWindow")
	links, err := q.GetDependenciesAt(t.Context(), fixtureEnd, time.Hour)
	if err != nil || len(links) != 2 {
		t.Fatalf("links %v %v", links, err)
	}
	// Sorted by parent, errorCount defaulted to 0.
	if links[0] != (ZipkinDependencyLink{Parent: "grow-frontend", Child: "grow-backend", CallCount: 1, ErrorCount: 1}) ||
		links[1] != (ZipkinDependencyLink{Parent: "grow-mixed", Child: "kafka-broker", CallCount: 1}) {
		t.Fatalf("links %+v", links)
	}
	if l, err := q.GetDependenciesAt(t.Context(), fixtureEnd, 0); err != nil || len(l) != 2 {
		t.Fatalf("default lookback %v %v", l, err)
	}
	if l, err := q.GetDependenciesAt(t.Context(), time.UnixMilli(1000), time.Second); err != nil || l == nil || len(l) != 0 {
		t.Fatalf("empty window %v %v", l, err)
	}
	spec, err := q.GetDependencies(t.Context(), DependencyOptions{Start: fixtureEnd.Add(-time.Hour), End: fixtureEnd})
	if err != nil || len(spec) != 2 || spec[0] != (DependencyLink{Parent: "grow-frontend", Child: "grow-backend", CallCount: 1}) {
		t.Fatalf("spec %v %v", spec, err)
	}
	if _, err := q.GetDependencies(t.Context(), DependencyOptions{Start: fixtureEnd, End: fixtureEnd}); !errors.Is(err, ErrValidation) {
		t.Fatalf("empty range: %v", err)
	}
	if _, err := q.GetDependenciesAt(t.Context(), fixtureEnd, -time.Second); !errors.Is(err, ErrValidation) {
		t.Fatal(err)
	}
	if _, err := q.GetDependenciesAt(t.Context(), time.UnixMilli(-1), 0); !errors.Is(err, ErrValidation) {
		t.Fatal(err)
	}
	// Zero end means now.
	now := newTestServer(t, func(w http.ResponseWriter, r *http.Request, _ int) {
		end := r.URL.Query().Get("endTs")
		if len(end) != 13 || r.URL.Query().Get("lookback") != "" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		_, _ = w.Write([]byte(`null`))
	})
	nq := newTestClient(t, now.URL, nil).Query()
	if l, err := nq.GetDependencies(t.Context(), DependencyOptions{}); err != nil || l == nil {
		t.Fatalf("now %v %v", l, err)
	}
	failing := newTestServer(t, status(http.StatusBadRequest))
	if _, err := newTestClient(t, failing.URL, nil).Query().GetDependencies(t.Context(), DependencyOptions{}); !errors.Is(err, ErrValidation) {
		t.Fatal(err)
	}
}

func TestSearchTraces(t *testing.T) {
	var got url.Values
	srv := newTestServer(t, func(w http.ResponseWriter, r *http.Request, _ int) {
		got = r.URL.Query()
		w.Header().Set("Content-Type", "application/json")
		body := loadFixture(t, "getTraces.defaults").Response.Body
		_, _ = w.Write(body)
	})
	q := newTestClient(t, srv.URL, nil).Query()
	end := fixtureEnd
	sums, err := q.SearchTraces(t.Context(), TraceSearchQuery{
		Service:     "grow-frontend",
		Operation:   "get /api/orders",
		Tags:        map[string]string{"http.method": "GET", "grow.test": "run-a"},
		MinDuration: time.Millisecond,
		MaxDuration: time.Second,
		Start:       end.Add(-time.Hour),
		End:         end,
	})
	if err != nil {
		t.Fatal(err)
	}
	want := url.Values{
		"serviceName": {"grow-frontend"}, "spanName": {"get /api/orders"},
		"annotationQuery": {"grow.test=run-a and http.method=GET"},
		"minDuration":     {"1000"}, "maxDuration": {"1000000"},
		"endTs": {"1795035940000"}, "lookback": {"3600000"}, "limit": {"20"},
	}
	if got.Encode() != want.Encode() {
		t.Fatalf("params\n got %s\nwant %s", got.Encode(), want.Encode())
	}
	if len(sums) != 8 {
		t.Fatalf("summaries %d", len(sums))
	}
	var order TraceSummary
	for _, s := range sums {
		if s.TraceID == "5af7183fb1d4cf5fad1c0df61d0a9b8c" {
			order = s
		}
	}
	if order.RootName != "get /api/orders" || order.SpanCount != 3 || order.DurationMicros != 150000 {
		t.Fatalf("summary %+v", order)
	}

	// A tag value with " and " cannot be sent: it is matched client-side.
	sums, err = q.SearchTraces(t.Context(), TraceSearchQuery{Tags: map[string]string{"grow.test": "run-a", "note": "cats and dogs"}, Limit: 3})
	if err != nil || got.Get("annotationQuery") != "grow.test=run-a" || got.Get("limit") != "3" || got.Get("lookback") != "3600000" || len(sums) != 0 {
		t.Fatalf("local filter: %v %v %v", got, sums, err)
	}
	sums, err = q.SearchTraces(t.Context(), TraceSearchQuery{Tags: map[string]string{"grow.test": "run-b and more"}})
	if err != nil || got.Get("annotationQuery") != "" || len(sums) != 0 {
		t.Fatalf("local filter only: %v %v", sums, err)
	}
	sums, err = q.SearchTraces(t.Context(), TraceSearchQuery{Tags: map[string]string{"k=v": "x", "error": "boom"}})
	if err != nil || got.Get("annotationQuery") != "error=boom" || len(sums) != 0 {
		t.Fatalf("key with '=': %v %v", sums, err)
	}
	// Start without End: End is now.
	if _, err := q.SearchTraces(t.Context(), TraceSearchQuery{Start: time.Now().Add(-time.Minute)}); err != nil || got.Get("lookback") == "" {
		t.Fatalf("start only: %v %v", got, err)
	}
	if _, err := q.SearchTraces(t.Context(), TraceSearchQuery{Start: end, End: end.Add(-time.Second)}); !errors.Is(err, ErrValidation) {
		t.Fatalf("bad range: %v", err)
	}
	if _, err := q.SearchTraces(t.Context(), TraceSearchQuery{MaxDuration: time.Second}); !errors.Is(err, ErrValidation) {
		t.Fatalf("max without min: %v", err)
	}
}

func TestWaitForTrace(t *testing.T) {
	q, _ := queryClient(t, "getTrace.ok", "getTrace.notFound")
	tr, err := q.WaitForTrace(t.Context(), "5af7183fb1d4cf5fad1c0df61d0a9b8c", WaitForTraceOptions{MinSpans: 3, Interval: time.Millisecond})
	if err != nil || len(tr.Spans) != 3 {
		t.Fatalf("wait %v %v", tr, err)
	}
	_, err = q.WaitForTrace(t.Context(), "ffffffffffffffffffffffffffffffff", WaitForTraceOptions{Timeout: 30 * time.Millisecond, Interval: 5 * time.Millisecond})
	if !errors.Is(err, ErrTimeout) {
		t.Fatalf("timeout: %v", err)
	}
	if _, err := q.WaitForTrace(t.Context(), "zz", WaitForTraceOptions{}); !errors.Is(err, ErrValidation) {
		t.Fatalf("invalid: %v", err)
	}
}

func TestGetTracesTerms(t *testing.T) {
	var got url.Values
	srv := newTestServer(t, func(w http.ResponseWriter, r *http.Request, _ int) {
		got = r.URL.Query()
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(loadFixture(t, "getTraces.defaults").Response.Body)
	})
	q := newTestClient(t, srv.URL, nil).Query()
	all, err := q.GetTraces(t.Context(), TracesQuery{})
	if err != nil {
		t.Fatal(err)
	}
	traces, err := q.GetTraces(t.Context(), TracesQuery{
		AnnotationQuery: " http.method=GET ",
		Tags:            map[string]string{"grow.test": "run-a", "note": "cats and dogs"},
		Annotations:     []string{"cache.miss", "a and b"},
	})
	if err != nil || got.Get("annotationQuery") != "http.method=GET and grow.test=run-a and cache.miss" || len(traces) != 0 {
		t.Fatalf("terms %v %d %v", got, len(traces), err)
	}
	// Client-side bare terms match a tag key or an annotation value.
	traces, err = q.GetTraces(t.Context(), TracesQuery{Annotations: []string{"x and y"}, Tags: map[string]string{"k=v": "1"}})
	if err != nil || got.Get("annotationQuery") != "" || len(traces) != 0 {
		t.Fatalf("no match %v %v", traces, err)
	}
	spans := fixtureSpans(t, "getTrace.ok")
	if !(term{key: "cache.miss", bare: true}).matches(spans) || !(term{key: "error", bare: true}).matches(spans) ||
		(term{key: "nope", bare: true}).matches(spans) || !(term{key: "error", value: "boom"}).matches(spans) ||
		(term{key: "error", value: "other"}).matches(spans) {
		t.Fatal("term matching")
	}
	if len(all) != 8 {
		t.Fatalf("all %d", len(all))
	}
	for _, bad := range []TracesQuery{{Tags: map[string]string{"": "v"}}, {Annotations: []string{""}}} {
		if _, err := q.GetTraces(t.Context(), bad); !errors.Is(err, ErrValidation) {
			t.Fatalf("empty term: %v", err)
		}
	}
}
