package zipkin

import (
	"context"
	"net/http"

	sdktrace "go.opentelemetry.io/otel/sdk/trace"
)

// ServerInfo is the build information of a Zipkin server (GET /info).
type ServerInfo struct {
	// Version is the Zipkin version, e.g. "3.6.1" (empty when not reported).
	Version string `json:"version,omitempty"`
	// Commit is the short git commit (empty when not reported).
	Commit string `json:"commit,omitempty"`
}

// ServerInfo returns the build information from GET /info
// ({"zipkin":{"version","commit"}}). It is empty, without an error, when the server or
// a gateway answers 404.
func (c *Client) ServerInfo(ctx context.Context) (ServerInfo, error) {
	res, err := c.transport.do(ctx, &request{Method: http.MethodGet, Path: "/info", Operation: "serverVersion"})
	if err != nil {
		if CodeOf(err) == ErrorCodeNotFound {
			return ServerInfo{}, nil
		}
		return ServerInfo{}, err
	}
	var body struct {
		Zipkin ServerInfo `json:"zipkin"`
	}
	if err := res.decodeJSON("serverVersion", &body); err != nil {
		return ServerInfo{}, err
	}
	return body.Zipkin, nil
}

// CreateReporter returns a reporter with other options (compression, batch size,
// endpoint). It shares the client's connections when the endpoint lives under the
// client's base URL, otherwise it owns connections built from the client's settings
// (headers, TLS, proxy, retries, hooks). Client.Close also closes it.
func (c *Client) CreateReporter(opts ReporterOptions) (*Reporter, error) {
	if c.closed.Load() {
		return nil, &Error{Code: ErrorCodeCancelled, Message: "zipkin.reporter: client is closed"}
	}
	r, err := newReporter(c.cfg, c.transport, opts)
	if err != nil {
		return nil, err
	}
	c.mu.Lock()
	c.reporters = append(c.reporters, r)
	c.mu.Unlock()
	return r, nil
}

// InitTracing starts OpenTelemetry tracing to this Zipkin (see the package-level
// InitTracing for the environment and defaults). With the native protocol and no
// explicit cfg.Endpoint, spans go to the client's Config.Endpoint through the client's
// connections, headers, TLS, proxy, retries and hooks; cfg.Headers and cfg.TLS, when
// set, are applied on top. Close shuts the handle down (flushing the batch processor).
// It returns a CONFIG error once the client is closed.
func (c *Client) InitTracing(ctx context.Context, cfg TracingConfig) (*TracingHandle, error) {
	if c.closed.Load() {
		return nil, ConfigError("initTracing: client is closed")
	}
	resolved, err := TracingConfigFromEnvWith(cfg, nil)
	if err != nil {
		return nil, err
	}
	if resolved.Protocol == ProtocolNative && cfg.Endpoint == "" {
		resolved.Endpoint = c.cfg.Endpoint
	}
	h, err := InitTracingWith(ctx, resolved, c.exporterFactory)
	if err != nil {
		return nil, err
	}
	c.mu.Lock()
	c.handles = append(c.handles, h)
	c.mu.Unlock()
	return h, nil
}

// exporterFactory builds the native exporter on the client's settings; OTLP protocols
// use NewExporter (an OpenTelemetry Collector endpoint).
func (c *Client) exporterFactory(ctx context.Context, cfg TracingConfig) (sdktrace.SpanExporter, error) {
	cfg = zipkinTracingDefaults(cfg.WithDefaults())
	if cfg.Protocol != ProtocolNative {
		return NewExporter(ctx, cfg)
	}
	zcfg := c.cfg
	shared := c.transport
	if len(cfg.Headers) > 0 || cfg.TLS != (TLSConfig{}) {
		headers := make(map[string]string, len(zcfg.Headers)+len(cfg.Headers))
		for k, v := range zcfg.Headers {
			headers[k] = v
		}
		for k, v := range cfg.Headers {
			headers[k] = v
		}
		zcfg.Headers = headers
		if cfg.TLS != (TLSConfig{}) {
			zcfg.TLS = cfg.TLS
		}
		shared = nil
	}
	rep, err := newReporter(zcfg, shared, ReporterOptions{Compression: cfg.Compression, MaxBatchSize: cfg.Batch.MaxExportBatchSize, Endpoint: cfg.Endpoint})
	if err != nil {
		return nil, err
	}
	return &SpanExporter{reporter: rep, owned: true}, nil
}
