package zipkin

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Client is the Zipkin client. It is safe for concurrent use.
//
// Health, Ping and ServerVersion use the server's own endpoints; the query API
// hangs off Query and the span ingest API off Reporter. Both share the client's
// connections, retries, hooks and redaction.
type Client struct {
	cfg       Config
	transport *httpTransport
	query     *QueryClient
	reporter  *Reporter
	closeOnce sync.Once
}

// NewClient validates cfg and returns a client.
func NewClient(cfg Config) (*Client, error) {
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	cfg.CommonConfig = cfg.withDefaults(DefaultURL)
	cfg.Endpoint = cfg.SpansEndpoint()
	t, err := newZipkinTransport(cfg.CommonConfig)
	if err != nil {
		return nil, err
	}
	c := &Client{cfg: cfg, transport: t}
	c.query = &QueryClient{transport: t}
	rep, err := newReporter(cfg, t, ReporterOptions{})
	if err != nil {
		t.close()
		return nil, err
	}
	c.reporter = rep
	return c, nil
}

// NewClientFromEnv builds a client from the environment (see ConfigFromEnv).
func NewClientFromEnv() (*Client, error) {
	cfg, err := ConfigFromEnv()
	if err != nil {
		return nil, err
	}
	return NewClient(cfg)
}

// newZipkinTransport builds the HTTP transport and teaches it Zipkin's error bodies.
func newZipkinTransport(cfg CommonConfig) (*httpTransport, error) {
	t, err := newHTTPTransport(cfg)
	if err != nil {
		return nil, err
	}
	t.client.Transport = textErrorTransport{base: t.client.Transport}
	return t, nil
}

// textErrorTransport rewrites Zipkin's "content-type: text/*" on error responses to
// text/plain so that the plain-text error message ends up in the SDK error message.
type textErrorTransport struct{ base http.RoundTripper }

// RoundTrip implements http.RoundTripper.
func (t textErrorTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	resp, err := t.base.RoundTrip(req)
	if err == nil && resp.StatusCode >= 400 && resp.Header.Get("Content-Type") == "text/*" {
		resp.Header.Set("Content-Type", "text/plain; charset=utf-8")
	}
	return resp, err
}

// Config returns the effective configuration (defaults applied).
func (c *Client) Config() Config { return c.cfg }

// Query returns the query API client (GET /api/v2/...). It implements TraceQueryClient.
func (c *Client) Query() *QueryClient { return c.query }

// Reporter returns the span reporter for Config.Endpoint (POST /api/v2/spans). It
// shares the client's connections when the endpoint is on the same server and is
// closed by Close.
func (c *Client) Reporter() *Reporter { return c.reporter }

// Health checks liveness with GET /health. Status is Zipkin's word: "UP", or "DOWN"
// (HTTP 503) when the storage fails; Details hold "httpStatus" and the "zipkin"
// component status. It reports an unreachable or unhealthy service in the result; it
// fails only for CANCELLED and CONFIG.
func (c *Client) Health(ctx context.Context) (HealthStatus, error) {
	res, err := c.transport.do(ctx, &request{Method: http.MethodGet, Path: "/health", Operation: "health", NoRetry: true, RawStatus: true})
	if err != nil {
		switch CodeOf(err) {
		case ErrorCodeCancelled, ErrorCodeConfig:
			return HealthStatus{}, err
		}
		return HealthStatus{Healthy: false, Status: "unreachable", Details: map[string]string{"error": err.Error()}}, nil
	}
	var body struct {
		Status string `json:"status"`
		Zipkin struct {
			Status string `json:"status"`
		} `json:"zipkin"`
	}
	_ = json.Unmarshal(res.Body, &body)
	ok := res.Status >= 200 && res.Status < 300
	hs := HealthStatus{Status: body.Status, Details: map[string]string{"httpStatus": strconv.Itoa(res.Status)}}
	if body.Zipkin.Status != "" {
		hs.Details["zipkin"] = body.Zipkin.Status
	}
	switch {
	case !ok:
		hs.Status = "DOWN"
	case hs.Status == "":
		hs.Status = "UP"
	}
	hs.Healthy = ok && strings.EqualFold(hs.Status, "UP")
	return hs, nil
}

// Ping makes a round trip through the query API and storage (GET /api/v2/services)
// and returns its latency. Behind a gateway it also proves the credentials in
// Headers (AUTHENTICATION error on HTTP 401).
func (c *Client) Ping(ctx context.Context) (time.Duration, error) {
	start := time.Now()
	_, err := c.transport.do(ctx, &request{Method: http.MethodGet, Path: "/api/v2/services", Operation: "ping"})
	if err != nil {
		return 0, err
	}
	return time.Since(start), nil
}

// ServerVersion returns the Zipkin version from GET /info ("3.6.1"). It returns
// UNSUPPORTED when the server exposes no version.
func (c *Client) ServerVersion(ctx context.Context) (string, error) {
	res, err := c.transport.do(ctx, &request{Method: http.MethodGet, Path: "/info", Operation: "serverVersion"})
	if err != nil {
		if CodeOf(err) == ErrorCodeNotFound {
			return "", UnsupportedError("serverVersion", "the server exposes no version")
		}
		return "", err
	}
	var body struct {
		Zipkin struct {
			Version string `json:"version"`
		} `json:"zipkin"`
	}
	if err := res.decodeJSON("serverVersion", &body); err != nil {
		return "", err
	}
	if body.Zipkin.Version == "" {
		return "", UnsupportedError("serverVersion", "the server exposes no version")
	}
	return body.Zipkin.Version, nil
}

// Close releases resources, including the reporter's connections. It is
// idempotent; later calls on the client fail with CANCELLED.
func (c *Client) Close() error {
	c.closeOnce.Do(func() {
		c.reporter.closeOwned()
		c.transport.close()
	})
	return nil
}
