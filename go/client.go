package zipkin

import (
	"context"
	"net/http"
	"sync"
	"time"
)

// Client is the Zipkin client. It is safe for concurrent use.
type Client struct {
	cfg       Config
	transport *httpTransport
	closeOnce sync.Once
}

// NewClient validates cfg and returns a client.
func NewClient(cfg Config) (*Client, error) {
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	cfg.CommonConfig = cfg.withDefaults(DefaultURL)
	t, err := newHTTPTransport(cfg.CommonConfig)
	if err != nil {
		return nil, err
	}
	t.addSecret(cfg.Token.Reveal())
	return &Client{cfg: cfg, transport: t}, nil
}

// NewClientFromEnv builds a client from the environment (see ConfigFromEnv).
func NewClientFromEnv() (*Client, error) {
	cfg, err := ConfigFromEnv()
	if err != nil {
		return nil, err
	}
	return NewClient(cfg)
}

// Config returns the effective configuration.
func (c *Client) Config() Config { return c.cfg }

func (c *Client) authHeader() http.Header {
	h := http.Header{}
	if !c.cfg.Token.IsZero() {
		h.Set("Authorization", "Bearer "+c.cfg.Token.Reveal())
	}
	return h
}

// Health checks liveness without credentials. It reports an unreachable or
// unhealthy service in the result; it fails only for CANCELLED and CONFIG.
func (c *Client) Health(ctx context.Context) (HealthStatus, error) {
	return c.transport.checkHealth(ctx, "health", &request{Method: http.MethodGet, Path: "/health"})
}

// Ping makes an authenticated round trip and returns its latency
// (AUTHENTICATION error on bad credentials).
func (c *Client) Ping(ctx context.Context) (time.Duration, error) {
	start := time.Now()
	_, err := c.transport.do(ctx, &request{Method: http.MethodGet, Path: "/v1/ping", Header: c.authHeader(), Operation: "ping"})
	if err != nil {
		return 0, err
	}
	return time.Since(start), nil
}

// ServerVersion returns the server version (UNSUPPORTED when the service exposes none).
func (c *Client) ServerVersion(ctx context.Context) (string, error) {
	res, err := c.transport.do(ctx, &request{Method: http.MethodGet, Path: "/version", Operation: "serverVersion"})
	if err != nil {
		if CodeOf(err) == ErrorCodeNotFound {
			return "", UnsupportedError("serverVersion", "the server exposes no version")
		}
		return "", err
	}
	var body struct {
		Version string `json:"version"`
	}
	if err := res.decodeJSON("serverVersion", &body); err != nil {
		return "", err
	}
	return body.Version, nil
}

// Close releases resources. It is idempotent.
func (c *Client) Close() error {
	c.closeOnce.Do(c.transport.close)
	return nil
}
