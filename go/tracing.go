package zipkin

import (
	"context"
	"errors"
	"net/url"
	"strconv"
	"strings"

	sdktrace "go.opentelemetry.io/otel/sdk/trace"
)

// DefaultTracingProtocol is the export protocol when none is configured: Zipkin v2
// JSON through SpanExporter.
const DefaultTracingProtocol = ProtocolNative

// DefaultPropagators are the propagators InitTracing uses when none are configured:
// W3C trace context and baggage plus B3 (single header). Add PropagatorB3Multi to also
// inject the X-B3-* headers; every B3 propagator extracts both formats.
var DefaultPropagators = []string{PropagatorTraceContext, PropagatorBaggage, PropagatorB3}

// InitTracing starts OpenTelemetry tracing that exports to Zipkin. Unset fields of cfg
// are filled from the environment (TracingConfigFromEnvWith), then the Zipkin defaults
// apply: protocol native (v2 JSON to POST /api/v2/spans through SpanExporter), endpoint
// http://127.0.0.1:9411/api/v2/spans, propagators tracecontext + baggage + b3, with W3C
// winning when an incoming request carries both W3C and B3 headers. ProtocolGRPC and
// ProtocolHTTPProtobuf use the official OTLP exporter and need an OpenTelemetry
// Collector with a zipkin exporter in front of Zipkin (stock Zipkin has no OTLP).
// Shut the handle down to flush.
func InitTracing(ctx context.Context, cfg TracingConfig) (*TracingHandle, error) {
	resolved, err := TracingConfigFromEnvWith(cfg, nil)
	if err != nil {
		return nil, err
	}
	return InitTracingWith(ctx, resolved, NewExporter)
}

// TracingConfigFromEnvWith fills the unset fields of explicit from the environment and
// applies the Zipkin defaults (explicit > environment > default):
//
//   - Protocol: GROW_ZIPKIN_PROTOCOL (native, grpc, http/protobuf), default native.
//   - Endpoint (native): GROW_ZIPKIN_ENDPOINT > OTEL_EXPORTER_ZIPKIN_ENDPOINT (verbatim) >
//     GROW_ZIPKIN_URL or ZIPKIN_URL + /api/v2/spans > http://127.0.0.1:9411/api/v2/spans.
//   - Endpoint (OTLP, an OpenTelemetry Collector): OTEL_EXPORTER_OTLP_TRACES_ENDPOINT
//     (verbatim) > OTEL_EXPORTER_OTLP_ENDPOINT (http/protobuf appends /v1/traces).
//   - Batch.ExportTimeout: OTEL_EXPORTER_ZIPKIN_TIMEOUT (milliseconds or "10s").
//   - Compression: GROW_ZIPKIN_COMPRESSION (gzip, none).
//   - ServiceName: OTEL_SERVICE_NAME, else service.name in OTEL_RESOURCE_ATTRIBUTES; the
//     other OTEL_RESOURCE_ATTRIBUTES fill unset ResourceAttributes.
//   - Sampler / SamplerRatio: OTEL_TRACES_SAMPLER / OTEL_TRACES_SAMPLER_ARG.
//   - Propagators: OTEL_PROPAGATORS, default DefaultPropagators; names are trimmed,
//     lower-cased, de-duplicated and B3 is moved before W3C so that W3C wins on
//     extraction when both are present.
//
// Headers come from code only. env replaces the process environment when non-nil. An
// invalid value is a CONFIG error naming the variable, never its value.
func TracingConfigFromEnvWith(explicit TracingConfig, env map[string]string) (TracingConfig, error) {
	r := NewEnvReader(EnvPrefix, env)
	c := explicit
	if c.ResourceAttributes != nil {
		attrs := make(map[string]any, len(c.ResourceAttributes))
		for k, v := range c.ResourceAttributes {
			attrs[k] = v
		}
		c.ResourceAttributes = attrs
	}
	if c.Protocol == "" {
		if v, name, ok := r.find("PROTOCOL", nil); ok {
			p := Protocol(strings.ToLower(strings.TrimSpace(v)))
			if p != ProtocolNative && p != ProtocolGRPC && p != ProtocolHTTPProtobuf {
				return TracingConfig{}, ConfigError(name + " must be native, grpc or http/protobuf")
			}
			c.Protocol = p
		} else {
			c.Protocol = DefaultTracingProtocol
		}
	}
	if c.Endpoint == "" {
		if c.Protocol == ProtocolNative {
			if v, ok := r.String("ENDPOINT", EnvOTelZipkinEndpoint); ok {
				c.Endpoint = strings.TrimSpace(v)
			} else if v, ok := r.String("URL", vendorEnv.URL...); ok {
				c.Endpoint = SpansEndpoint(strings.TrimSpace(v))
			}
		} else if v, ok := r.String("", "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"); ok {
			c.Endpoint = strings.TrimSpace(v)
		} else if v, ok := r.String("", "OTEL_EXPORTER_OTLP_ENDPOINT"); ok {
			c.Endpoint = strings.TrimSpace(v)
			if c.Protocol == ProtocolHTTPProtobuf {
				c.Endpoint = otlpHTTPTracesURL(strings.TrimRight(c.Endpoint, "/"))
			}
		}
	}
	if c.Batch.ExportTimeout == 0 {
		d, ok, err := r.Duration("", "OTEL_EXPORTER_ZIPKIN_TIMEOUT")
		if err != nil || (ok && d <= 0) {
			return TracingConfig{}, ConfigError("OTEL_EXPORTER_ZIPKIN_TIMEOUT is not a valid duration")
		}
		c.Batch.ExportTimeout = d
	}
	if c.Compression == "" {
		if v, name, ok := r.find("COMPRESSION", nil); ok {
			v = strings.ToLower(strings.TrimSpace(v))
			if v != CompressionGzip && v != CompressionNone {
				return TracingConfig{}, ConfigError(name + " must be gzip or none")
			}
			c.Compression = v
		}
	}
	resourceServiceName := ""
	if raw, name, ok := r.find("", []string{"OTEL_RESOURCE_ATTRIBUTES"}); ok {
		attrs, err := parseKeyValues(raw)
		if err != nil {
			return TracingConfig{}, ConfigError(name + " must be a list of key=value pairs")
		}
		resourceServiceName = attrs["service.name"]
		for k, v := range attrs {
			if k == "service.name" {
				continue
			}
			if _, set := c.ResourceAttributes[k]; !set {
				if c.ResourceAttributes == nil {
					c.ResourceAttributes = map[string]any{}
				}
				c.ResourceAttributes[k] = v
			}
		}
	}
	if strings.TrimSpace(c.ServiceName) == "" {
		c.ServiceName = resourceServiceName
		if v, ok := r.String("", "OTEL_SERVICE_NAME"); ok {
			c.ServiceName = strings.TrimSpace(v)
		}
	}
	if c.Sampler == "" {
		if v, name, ok := r.find("", []string{"OTEL_TRACES_SAMPLER"}); ok {
			c.Sampler = Sampler(strings.ToLower(strings.TrimSpace(v)))
			if err := (TracingConfig{ServiceName: "x", Sampler: c.Sampler}).Validate(); err != nil {
				return TracingConfig{}, ConfigError(name + " is not a known sampler")
			}
			if arg, argName, ok := r.find("", []string{"OTEL_TRACES_SAMPLER_ARG"}); ok && strings.HasSuffix(string(c.Sampler), "traceidratio") && c.SamplerRatio == 0 {
				ratio, err := strconv.ParseFloat(strings.TrimSpace(arg), 64)
				if err != nil || ratio < 0 || ratio > 1 {
					return TracingConfig{}, ConfigError(argName + " must be a ratio between 0 and 1")
				}
				c.SamplerRatio = ratio
			}
		}
	}
	if len(c.Propagators) == 0 {
		if v, ok := r.String("", "OTEL_PROPAGATORS"); ok {
			for _, p := range strings.Split(v, ",") {
				if p = strings.TrimSpace(p); p != "" {
					c.Propagators = append(c.Propagators, p)
				}
			}
		}
	}
	c = zipkinTracingDefaults(c)
	if err := c.Validate(); err != nil {
		return TracingConfig{}, err
	}
	return c, nil
}

// parseKeyValues parses "k=v,k2=v2" (W3C baggage style, percent-encoded).
func parseKeyValues(raw string) (map[string]string, error) {
	out := map[string]string{}
	for _, part := range strings.Split(raw, ",") {
		if strings.TrimSpace(part) == "" {
			continue
		}
		k, v, ok := strings.Cut(part, "=")
		k = strings.TrimSpace(k)
		if !ok || k == "" {
			return nil, errors.New("expected key=value")
		}
		key, err := url.PathUnescape(k)
		if err != nil {
			return nil, err
		}
		val, err := url.PathUnescape(strings.TrimSpace(v))
		if err != nil {
			return nil, err
		}
		out[key] = val
	}
	return out, nil
}

// TracingConfigFromEnv is TracingConfigFromEnvWith over the process environment.
func TracingConfigFromEnv(explicit TracingConfig) (TracingConfig, error) {
	return TracingConfigFromEnvWith(explicit, nil)
}

// zipkinTracingDefaults fills protocol, endpoint and propagators.
func zipkinTracingDefaults(c TracingConfig) TracingConfig {
	if c.Protocol == "" {
		c.Protocol = DefaultTracingProtocol
	}
	if c.Endpoint == "" && c.Protocol == ProtocolNative {
		c.Endpoint = SpansEndpoint(DefaultURL)
	}
	if len(c.Propagators) == 0 {
		c.Propagators = append([]string(nil), DefaultPropagators...)
	}
	c.Propagators = orderPropagators(c.Propagators)
	return c
}

// orderPropagators trims, lower-cases and de-duplicates the names and moves the B3
// propagators first (stable), so that the W3C propagators, applied later by the
// composite, win on extraction.
func orderPropagators(names []string) []string {
	out := make([]string, 0, len(names))
	var rest []string
	seen := map[string]bool{}
	for _, n := range names {
		n = strings.ToLower(strings.TrimSpace(n))
		if seen[n] {
			continue
		}
		seen[n] = true
		switch n {
		case PropagatorB3, PropagatorB3Multi:
			out = append(out, n)
		default:
			rest = append(rest, n)
		}
	}
	return append(out, rest...)
}

// NewExporter is the Zipkin ExporterFactory. ProtocolNative (and the empty protocol)
// builds a SpanExporter for cfg.Endpoint (default http://127.0.0.1:9411/api/v2/spans)
// with cfg.Headers, cfg.TLS and cfg.Compression; the exporter owns its reporter.
// ProtocolGRPC and ProtocolHTTPProtobuf build the official OTLP exporter
// (NewOTLPExporter) for an OpenTelemetry Collector.
func NewExporter(ctx context.Context, cfg TracingConfig) (sdktrace.SpanExporter, error) {
	cfg = zipkinTracingDefaults(cfg.WithDefaults())
	if cfg.Protocol != ProtocolNative {
		if cfg.Endpoint == "" || strings.HasSuffix(strings.TrimRight(cfg.Endpoint, "/"), SpansPath) {
			return nil, ConfigError("tracing: protocol grpc/http/protobuf needs the endpoint of an OpenTelemetry Collector with a zipkin exporter (stock Zipkin does not accept OTLP)")
		}
		return NewOTLPExporter(ctx, cfg)
	}
	zcfg := Config{
		CommonConfig: CommonConfig{
			URL:            BaseFromEndpoint(cfg.Endpoint),
			Headers:        cfg.Headers,
			TLS:            cfg.TLS,
			RequestTimeout: cfg.Batch.ExportTimeout,
			TotalTimeout:   cfg.Batch.ExportTimeout,
		},
		Endpoint: cfg.Endpoint,
	}
	rep, err := NewReporter(zcfg, ReporterOptions{Compression: cfg.Compression, MaxBatchSize: cfg.Batch.MaxExportBatchSize})
	if err != nil {
		return nil, err
	}
	return &SpanExporter{reporter: rep, owned: true}, nil
}
