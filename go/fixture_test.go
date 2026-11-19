package zipkin

import (
	"encoding/json"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// fixture is a request/response pair recorded against openzipkin/zipkin:3.6.1
// (testdata/fixtures, written by the prep verify.sh).
type fixture struct {
	Name    string
	Request struct {
		Method  string          `json:"method"`
		Path    string          `json:"path"`
		Headers []string        `json:"headers"`
		Body    json.RawMessage `json:"body"`
	} `json:"request"`
	Response struct {
		Status  int             `json:"status"`
		Headers []string        `json:"headers"`
		Body    json.RawMessage `json:"body"`
	} `json:"response"`
}

func loadFixture(t *testing.T, name string) fixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "fixtures", name+".json"))
	if err != nil {
		t.Fatalf("fixture %s: %v", name, err)
	}
	var f fixture
	if err := json.Unmarshal(data, &f); err != nil {
		t.Fatalf("fixture %s: %v", name, err)
	}
	f.Name = name
	return f
}

// matches reports whether r is the fixture's request (method, path and query; the
// query parameter order does not matter).
func (f fixture) matches(r *http.Request) bool {
	u, err := url.Parse(f.Request.Path)
	if err != nil || r.Method != f.Request.Method || r.URL.Path != u.Path {
		return false
	}
	return reflect.DeepEqual(normQuery(u.Query()), normQuery(r.URL.Query()))
}

func normQuery(v url.Values) url.Values {
	if len(v) == 0 {
		return url.Values{}
	}
	return v
}

// write replays the recorded response.
func (f fixture) write(w http.ResponseWriter) {
	contentType := ""
	for _, h := range f.Response.Headers {
		k, v, _ := strings.Cut(h, ":")
		w.Header().Set(strings.TrimSpace(k), strings.TrimSpace(v))
		if strings.EqualFold(strings.TrimSpace(k), "content-type") {
			contentType = strings.TrimSpace(v)
		}
	}
	w.WriteHeader(f.Response.Status)
	body := f.Response.Body
	if len(body) == 0 || string(body) == "null" {
		return
	}
	if strings.Contains(contentType, "json") {
		_, _ = w.Write(body)
		return
	}
	var text string
	if json.Unmarshal(body, &text) == nil {
		_, _ = w.Write([]byte(text))
		return
	}
	_, _ = w.Write(body)
}

// fixtureServer serves the named fixtures; unmatched requests get 599.
func fixtureServer(t *testing.T, names ...string) *testServer {
	t.Helper()
	fixtures := make([]fixture, len(names))
	for i, n := range names {
		fixtures[i] = loadFixture(t, n)
	}
	return newTestServer(t, func(w http.ResponseWriter, r *http.Request, _ int) {
		for _, f := range fixtures {
			if f.matches(r) {
				f.write(w)
				return
			}
		}
		w.WriteHeader(599)
		_, _ = w.Write([]byte("no fixture for " + r.Method + " " + r.URL.String()))
	})
}

// fixtureSpans decodes a fixture response body of spans.
func fixtureSpans(t *testing.T, name string) []ZipkinSpan {
	t.Helper()
	var spans []ZipkinSpan
	if err := json.Unmarshal(loadFixture(t, name).Response.Body, &spans); err != nil {
		t.Fatal(err)
	}
	return spans
}

// newTestClient builds a client for url with fast retries; mutate adjusts the config.
func newTestClient(t *testing.T, url string, mutate func(*Config)) *Client {
	t.Helper()
	cfg := Config{CommonConfig: CommonConfig{URL: url, Retry: fastRetry(2)}}
	if mutate != nil {
		mutate(&cfg)
	}
	c, err := NewClient(cfg)
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c
}
