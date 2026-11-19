package zipkin

import (
	"errors"
	"reflect"
	"testing"
)

type stringer struct{}

func (stringer) String() string { return "str" }

func TestTagValue(t *testing.T) {
	cases := []struct {
		in   any
		want string
	}{
		{nil, ""}, {"s", "s"}, {true, "true"}, {7, "7"}, {int64(-3), "-3"}, {int32(4), "4"},
		{uint64(5), "5"}, {1.5, "1.5"}, {float32(2.5), "2.5"}, {123456789.0, "123456789"},
		{stringer{}, "str"}, {[]string{"a", "b"}, `["a","b"]`}, {map[string]int{"k": 1}, `{"k":1}`},
		{func() {}, "0x"},
	}
	for _, c := range cases {
		got := TagValue(c.in)
		if c.want == "0x" {
			if got == "" {
				t.Errorf("func: empty")
			}
			continue
		}
		if got != c.want {
			t.Errorf("TagValue(%v) = %q, want %q", c.in, got, c.want)
		}
	}
	if got := StringTags(map[string]any{"b": false, "n": 7}); !reflect.DeepEqual(got, map[string]string{"b": "false", "n": "7"}) {
		t.Fatal(got)
	}
}

func TestNormalize(t *testing.T) {
	good := ZipkinSpan{
		TraceID:        "5AF7183FB1D4CF5FAD1C0DF61D0A9B8C",
		ParentID:       "000000000000000A",
		ID:             "000000000000000B",
		Kind:           "client",
		Name:           "Get",
		Timestamp:      1,
		Duration:       2,
		LocalEndpoint:  &Endpoint{ServiceName: "svc", Port: 80},
		RemoteEndpoint: &Endpoint{},
		Annotations:    []Annotation{{Timestamp: 3, Value: "a"}},
		Tags:           map[string]string{"k": "v"},
	}
	n, err := good.Normalize()
	if err != nil {
		t.Fatal(err)
	}
	if n.TraceID != "5af7183fb1d4cf5fad1c0df61d0a9b8c" || n.ParentID != "000000000000000a" || n.ID != "000000000000000b" ||
		n.Kind != KindClient || n.RemoteEndpoint != nil || n.LocalEndpoint == good.LocalEndpoint || n.Tags["k"] != "v" {
		t.Fatalf("normalized %+v", n)
	}
	n.Tags["k"] = "changed"
	if good.Tags["k"] != "v" {
		t.Fatal("Normalize must copy tags")
	}
	min := ZipkinSpan{TraceID: "463ac35cad1c0df6", ID: "00000000000000b1", ParentID: "  ", Tags: map[string]string{}}
	if n, err := min.Normalize(); err != nil || n.ParentID != "" || n.Tags != nil || n.Annotations != nil {
		t.Fatalf("minimal %+v %v", n, err)
	}

	bad := []ZipkinSpan{
		{TraceID: "zz", ID: "00000000000000b1"},
		{TraceID: "00000000000000000000000000000000", ID: "00000000000000b1"},
		{TraceID: "463ac35cad1c0df6", ID: "1"},
		{TraceID: "463ac35cad1c0df6", ID: "00000000000000b1", ParentID: "xyz"},
		{TraceID: "463ac35cad1c0df6", ID: "00000000000000b1", Kind: "internal"},
		{TraceID: "463ac35cad1c0df6", ID: "00000000000000b1", Timestamp: -1},
		{TraceID: "463ac35cad1c0df6", ID: "00000000000000b1", LocalEndpoint: &Endpoint{Port: 70000}},
		{TraceID: "463ac35cad1c0df6", ID: "00000000000000b1", RemoteEndpoint: &Endpoint{Port: -1}},
		{TraceID: "463ac35cad1c0df6", ID: "00000000000000b1", Annotations: []Annotation{{Timestamp: 1}}},
		{TraceID: "463ac35cad1c0df6", ID: "00000000000000b1", Annotations: []Annotation{{Value: "x"}}},
	}
	for i, b := range bad {
		if _, err := b.Normalize(); !errors.Is(err, ErrValidation) {
			t.Errorf("case %d: %v", i, err)
		}
	}
}

func TestToSpanAndTrace(t *testing.T) {
	spans := fixtureSpans(t, "getTrace.ok")
	tr := TraceFromZipkin(spans)
	if tr == nil || tr.TraceID != "5af7183fb1d4cf5fad1c0df61d0a9b8c" || len(tr.Spans) != 3 {
		t.Fatalf("trace %+v", tr)
	}
	root := tr.Spans[0]
	if root.Kind != SpanKindServer || root.ServiceName != "grow-frontend" || root.ParentSpanID != "" ||
		root.DurationMicros != 150000 || root.EndMicros != root.StartMicros+150000 ||
		len(root.Events) != 1 || root.Events[0].Name != "cache.miss" || root.Attributes["http.method"] != "GET" ||
		root.Status.Code != SpanStatusUnset || len(root.Links) != 0 {
		t.Fatalf("root %+v", root)
	}
	if s := tr.Spans[2]; s.Status.Code != SpanStatusError || s.Status.Message != "boom" || s.ParentSpanID != "0000000000000002" {
		t.Fatalf("error span %+v", s)
	}
	if tree := tr.Tree(); len(tree) != 1 || len(tree[0].Children) != 1 {
		t.Fatalf("tree %+v", tree)
	}
	if TraceFromZipkin(nil) != nil {
		t.Fatal("empty trace")
	}

	id64 := fixtureSpans(t, "getTrace.id64")
	if s := id64[0].ToSpan(); s.TraceID != "0000000000000000463ac35cad1c0df6" || s.Kind != SpanKindInternal || s.ServiceName != "grow-lonely" {
		t.Fatalf("id64 %+v", s)
	}
	kinds := map[Kind]SpanKind{KindProducer: SpanKindProducer, KindConsumer: SpanKindConsumer, KindClient: SpanKindClient, "ODD": SpanKindUnspecified}
	for k, want := range kinds {
		if got := (ZipkinSpan{TraceID: "1", ID: "1", Kind: k}).ToSpan().Kind; got != want {
			t.Errorf("kind %s → %s", k, got)
		}
	}
	if s := (ZipkinSpan{TraceID: "not-hex", ID: "AB", LocalEndpoint: nil}).ToSpan(); s.TraceID != "not-hex" || s.SpanID != "ab" || s.ServiceName != "" {
		t.Fatalf("bad id %+v", s)
	}
	status := map[string]SpanStatusCode{"OK": SpanStatusOK, "ERROR": SpanStatusError, "x": SpanStatusUnset}
	for v, want := range status {
		if got := (ZipkinSpan{Tags: map[string]string{"otel.status_code": v}}).ToSpan().Status.Code; got != want {
			t.Errorf("status %s → %s", v, got)
		}
	}
	if st := (ZipkinSpan{Tags: map[string]string{"error": ""}}).ToSpan().Status; st.Code != SpanStatusError || st.Message != "" {
		t.Fatalf("empty error %+v", st)
	}
}

func TestAnnotationToEvent(t *testing.T) {
	ev := AnnotationToEvent(Annotation{Timestamp: 5, Value: `{"py-event": {"k": "v", "n": 7}}`})
	if ev.Name != "py-event" || ev.TimeMicros != 5 || ev.Attributes["k"] != "v" || ev.Attributes["n"] != int64(7) {
		t.Fatalf("event %+v", ev)
	}
	for _, v := range []string{"plain", `{"a":1}`, `{"a":{},"b":{}}`, `{not json}`} {
		if ev := AnnotationToEvent(Annotation{Value: v}); ev.Name != v || len(ev.Attributes) != 0 {
			t.Errorf("%s → %+v", v, ev)
		}
	}
}

func TestSummarizeZipkinTrace(t *testing.T) {
	s, ok := SummarizeZipkinTrace(fixtureSpans(t, "getTrace.ok"))
	if !ok || s.RootName != "get /api/orders" || s.RootServiceName != "grow-frontend" || s.SpanCount != 3 ||
		s.StartMicros != 1795035874000000 || s.DurationMicros != 150000 {
		t.Fatalf("summary %+v", s)
	}
	// No span without a parent in the trace: the earliest span is the root.
	orphans := []ZipkinSpan{
		{TraceID: "463ac35cad1c0df6", ID: "0000000000000002", ParentID: "00000000000000ff", Name: "late", Timestamp: 20, Duration: 50},
		{TraceID: "463ac35cad1c0df6", ID: "0000000000000001", ParentID: "00000000000000fe", Name: "early", Timestamp: 10, Duration: 5},
	}
	s, ok = SummarizeZipkinTrace(orphans)
	if !ok || s.RootName != "early" || s.StartMicros != 10 || s.DurationMicros != 60 {
		t.Fatalf("orphans %+v", s)
	}
	if _, ok := SummarizeZipkinTrace(nil); ok {
		t.Fatal("empty")
	}
}

func TestDependencyLink(t *testing.T) {
	l := ZipkinDependencyLink{Parent: "a", Child: "b", CallCount: 3, ErrorCount: 1}
	if l.Link() != (DependencyLink{Parent: "a", Child: "b", CallCount: 3}) {
		t.Fatal(l.Link())
	}
}
