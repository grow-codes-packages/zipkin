package zipkin

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"strings"
	"sync"
)

// DefaultReporterBatchSize is the default number of spans per POST.
const DefaultReporterBatchSize = 512

// ReporterOptions configure a Reporter. Zero values mean defaults.
type ReporterOptions struct {
	// Compression is CompressionGzip (Content-Encoding: gzip) or CompressionNone
	// (default).
	Compression string
	// MaxBatchSize is the number of spans per POST (default 512); larger reports are
	// split into several requests, in order.
	MaxBatchSize int
	// Endpoint overrides Config.Endpoint (the full span ingest URL).
	Endpoint string
}

// Reporter sends spans to Zipkin without OpenTelemetry: POST /api/v2/spans with a JSON
// array of v2 spans (HTTP 202). Spans are normalized first (ZipkinSpan.Normalize), so an
// invalid span is a VALIDATION error before anything is sent. Ingest is idempotent
// (Zipkin merges spans by id), so HTTP 5xx, 429 and network errors are retried; HTTP
// 400 and 413 are permanent VALIDATION errors.
//
// Get one from Client.Reporter (shares the client's connections) or NewReporter (owns
// its connections; call Close). It is safe for concurrent use.
type Reporter struct {
	transport   *httpTransport
	owned       bool
	endpoint    string
	path        string
	query       url.Values
	compression string
	batchSize   int
	closeOnce   sync.Once
}

// NewReporter builds a standalone reporter for cfg.Endpoint (or opts.Endpoint) with its
// own connections. Close releases them.
func NewReporter(cfg Config, opts ReporterOptions) (*Reporter, error) {
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	cfg.CommonConfig = cfg.withDefaults(DefaultURL)
	return newReporter(cfg, nil, opts)
}

// NewReporterFromEnv builds a standalone reporter from the environment (ConfigFromEnv).
func NewReporterFromEnv(opts ReporterOptions) (*Reporter, error) {
	cfg, err := ConfigFromEnv()
	if err != nil {
		return nil, err
	}
	return NewReporter(cfg, opts)
}

// newReporter builds a reporter. shared is the client's transport (nil for a standalone
// reporter); it is reused when the endpoint lives under the client's base URL.
func newReporter(cfg Config, shared *httpTransport, opts ReporterOptions) (*Reporter, error) {
	compression := opts.Compression
	if compression == "" {
		compression = CompressionNone
	}
	if compression != CompressionGzip && compression != CompressionNone {
		return nil, ConfigError("reporter: compression must be gzip or none")
	}
	if opts.MaxBatchSize < 0 {
		return nil, ConfigError("reporter: maxBatchSize must be positive")
	}
	batch := opts.MaxBatchSize
	if batch == 0 {
		batch = DefaultReporterBatchSize
	}
	endpoint := opts.Endpoint
	if endpoint == "" {
		endpoint = cfg.SpansEndpoint()
	}
	ep, err := url.Parse(endpoint)
	if err != nil || (ep.Scheme != "http" && ep.Scheme != "https") || ep.Host == "" {
		return nil, ConfigError("reporter: endpoint must be an absolute http or https URL")
	}
	r := &Reporter{endpoint: endpoint, query: ep.Query(), compression: compression, batchSize: batch}
	if shared != nil {
		if rel, ok := relativePath(shared.base, ep); ok {
			r.transport, r.path = shared, rel
			return r, nil
		}
	}
	common := cfg.CommonConfig
	common.URL = ep.Scheme + "://" + ep.Host
	t, err := newZipkinTransport(common.withDefaults(DefaultURL))
	if err != nil {
		return nil, err
	}
	r.transport, r.owned, r.path = t, true, ep.Path
	return r, nil
}

// relativePath returns the path of ep relative to base when ep is on the same origin
// and below base's path.
func relativePath(base, ep *url.URL) (string, bool) {
	if !strings.EqualFold(base.Scheme, ep.Scheme) || !strings.EqualFold(base.Host, ep.Host) {
		return "", false
	}
	prefix := strings.TrimSuffix(base.Path, "/")
	if prefix != "" && ep.Path != prefix && !strings.HasPrefix(ep.Path, prefix+"/") {
		return "", false
	}
	return strings.TrimPrefix(ep.Path, prefix), true
}

// Endpoint returns the span ingest URL.
func (r *Reporter) Endpoint() string { return r.endpoint }

// Report sends spans. An empty slice sends nothing; more than MaxBatchSize spans are
// sent in several POSTs, in order (an error stops the remaining batches). Spans are
// queryable once the call returns with in-memory storage; persistent backends may lag
// (use QueryClient.WaitForTrace).
func (r *Reporter) Report(ctx context.Context, spans []ZipkinSpan) error {
	normalized := make([]ZipkinSpan, len(spans))
	for i, s := range spans {
		n, err := s.Normalize()
		if err != nil {
			return err
		}
		normalized[i] = n
	}
	for start := 0; start < len(normalized); start += r.batchSize {
		end := min(start+r.batchSize, len(normalized))
		if err := r.send(ctx, normalized[start:end]); err != nil {
			return err
		}
	}
	return nil
}

func (r *Reporter) send(ctx context.Context, spans []ZipkinSpan) error {
	body, err := json.Marshal(spans)
	if err != nil {
		return ValidationError("zipkin.report", "spans cannot be encoded")
	}
	header := http.Header{}
	if r.compression == CompressionGzip {
		var buf bytes.Buffer
		zw := gzip.NewWriter(&buf)
		_, _ = zw.Write(body)
		_ = zw.Close()
		body = buf.Bytes()
		header.Set("Content-Encoding", "gzip")
	}
	_, err = r.transport.do(ctx, &request{
		Method:      http.MethodPost,
		Path:        r.path,
		Query:       r.query,
		Header:      header,
		Body:        body,
		ContentType: "application/json",
		Idempotent:  true,
		Operation:   "zipkin.report",
	})
	return err
}

// closeOwned releases the reporter's own connections (no-op for a shared transport).
func (r *Reporter) closeOwned() {
	r.closeOnce.Do(func() {
		if r.owned {
			r.transport.close()
		}
	})
}

// Close releases the connections this reporter owns (a reporter from Client.Reporter
// is closed by Client.Close). It is idempotent.
func (r *Reporter) Close() error {
	r.closeOwned()
	return nil
}
