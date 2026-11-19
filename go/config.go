package zipkin

import (
	"fmt"
	"net/url"
	"strings"
)

// DefaultURL is the default base URL of a Zipkin server.
const DefaultURL = "http://127.0.0.1:9411"

// SpansPath is the path of the Zipkin v2 span ingest API.
const SpansPath = "/api/v2/spans"

// Vendor environment variables read after GROW_ZIPKIN_* (in order).
const (
	// EnvZipkinURL is the base URL of the server (after GROW_ZIPKIN_URL).
	EnvZipkinURL = "ZIPKIN_URL"
	// EnvOTelZipkinEndpoint is the OpenTelemetry Zipkin exporter endpoint (full
	// span ingest URL). It sets Config.Endpoint verbatim and, when no URL variable
	// is set, the base URL (with one trailing /api/v2/spans removed).
	EnvOTelZipkinEndpoint = "OTEL_EXPORTER_ZIPKIN_ENDPOINT"
)

// vendorEnv lists the vendor variables consulted after GROW_ZIPKIN_*.
var vendorEnv = VendorEnv{URL: []string{EnvZipkinURL}}

// Config configures a Client. Build it with a struct literal or ConfigFromEnv;
// zero values mean "use the default". Call Validate to check it.
//
// Stock Zipkin has no authentication. When it runs behind a gateway, put the
// gateway credentials in Headers (for example "Authorization"): they are sent on
// every call (query, report, exporter) and redacted everywhere.
type Config struct {
	CommonConfig

	// Endpoint is the full span ingest URL used by the Reporter and the span
	// exporter (env GROW_ZIPKIN_ENDPOINT, then OTEL_EXPORTER_ZIPKIN_ENDPOINT).
	// Empty means URL + "/api/v2/spans" (a gateway path prefix in URL is kept).
	Endpoint string
}

// ConfigFromEnv reads the configuration from the process environment.
func ConfigFromEnv() (Config, error) { return ConfigFromEnvWith(Config{}, nil) }

// ConfigFromEnvWith resolves explicit values > GROW_ZIPKIN_* > vendor
// variables > defaults. env replaces the process environment when non-nil.
//
//   - URL: GROW_ZIPKIN_URL > ZIPKIN_URL > OTEL_EXPORTER_ZIPKIN_ENDPOINT without its
//     trailing /api/v2/spans > http://127.0.0.1:9411.
//   - Endpoint: GROW_ZIPKIN_ENDPOINT > OTEL_EXPORTER_ZIPKIN_ENDPOINT (verbatim) >
//     URL + /api/v2/spans.
//   - Timeouts, retries, TLS and proxy: the common GROW_ZIPKIN_* variables.
func ConfigFromEnvWith(explicit Config, env map[string]string) (Config, error) {
	r := NewEnvReader(EnvPrefix, env)
	c := explicit
	if err := c.applyEnv(r, vendorEnv); err != nil {
		return Config{}, err
	}
	if c.URL == "" {
		if v, ok := r.String("", EnvOTelZipkinEndpoint); ok {
			c.URL = BaseFromEndpoint(strings.TrimSpace(v))
		}
	}
	if c.Endpoint == "" {
		if v, ok := r.String("ENDPOINT", EnvOTelZipkinEndpoint); ok {
			c.Endpoint = strings.TrimSpace(v)
		}
	}
	c.CommonConfig = c.withDefaults(DefaultURL)
	c.Endpoint = c.SpansEndpoint()
	if err := c.Validate(); err != nil {
		return Config{}, err
	}
	return c, nil
}

// SpansEndpoint returns Endpoint, or URL + "/api/v2/spans" when Endpoint is empty.
func (c Config) SpansEndpoint() string {
	if c.Endpoint != "" {
		return c.Endpoint
	}
	base := c.URL
	if base == "" {
		base = DefaultURL
	}
	return SpansEndpoint(base)
}

// SpansEndpoint appends "/api/v2/spans" to a base URL once: a URL that already ends
// with it is kept, a gateway path prefix is preserved and trailing slashes are dropped.
func SpansEndpoint(base string) string {
	trimmed := strings.TrimRight(base, "/")
	if strings.HasSuffix(trimmed, SpansPath) {
		return trimmed
	}
	return trimmed + SpansPath
}

// BaseFromEndpoint returns the server base URL of a span ingest URL: one trailing
// "/api/v2/spans" (and trailing slashes) is removed.
func BaseFromEndpoint(endpoint string) string {
	trimmed := strings.TrimRight(endpoint, "/")
	return strings.TrimSuffix(trimmed, SpansPath)
}

// Validate checks the configuration and returns a CONFIG error.
func (c Config) Validate() error {
	if err := c.withDefaults(DefaultURL).validate(); err != nil {
		return err
	}
	if c.Endpoint != "" {
		u, err := url.Parse(c.Endpoint)
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
			return ConfigError("endpoint must be an absolute http or https URL")
		}
	}
	return nil
}

// String renders the configuration with every credential redacted.
func (c Config) String() string {
	return fmt.Sprintf("Config{%s Endpoint:%s}", c.describe(), RedactURL(c.SpansEndpoint()))
}

// GoString renders the configuration with every credential redacted.
func (c Config) GoString() string { return c.String() }
