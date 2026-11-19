package zipkin

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestConfigFromEnv(t *testing.T) {
	t.Run("core.config.env-precedence", func(t *testing.T) {
		env := map[string]string{
			EnvPrefix + "URL":     "http://grow:1",
			EnvZipkinURL:          "http://vendor:2",
			EnvOTelZipkinEndpoint: "http://otel:3/prefix/api/v2/spans",
		}
		cfg, err := ConfigFromEnvWith(Config{}, env)
		if err != nil || cfg.URL != "http://grow:1" || cfg.Endpoint != "http://otel:3/prefix/api/v2/spans" {
			t.Fatalf("grow url: %v %s", err, cfg)
		}
		delete(env, EnvPrefix+"URL")
		cfg, _ = ConfigFromEnvWith(Config{}, env)
		if cfg.URL != "http://vendor:2" {
			t.Fatalf("vendor url: %s", cfg.URL)
		}
		delete(env, EnvZipkinURL)
		cfg, _ = ConfigFromEnvWith(Config{}, env)
		if cfg.URL != "http://otel:3/prefix" {
			t.Fatalf("otel base: %s", cfg.URL)
		}
		env[EnvPrefix+"ENDPOINT"] = "https://gw.example/zipkin/api/v2/spans"
		cfg, _ = ConfigFromEnvWith(Config{}, env)
		if cfg.Endpoint != "https://gw.example/zipkin/api/v2/spans" {
			t.Fatalf("grow endpoint: %s", cfg.Endpoint)
		}
		cfg, _ = ConfigFromEnvWith(Config{CommonConfig: CommonConfig{URL: "https://explicit"}, Endpoint: "https://explicit/x"}, env)
		if cfg.URL != "https://explicit" || cfg.Endpoint != "https://explicit/x" {
			t.Fatal("explicit must win")
		}
		cfg, _ = ConfigFromEnvWith(Config{}, map[string]string{})
		if cfg.URL != DefaultURL || cfg.Endpoint != DefaultURL+SpansPath || cfg.RequestTimeout != DefaultRequestTimeout {
			t.Fatalf("defaults: %s", cfg)
		}
		_, err = ConfigFromEnvWith(Config{}, map[string]string{EnvPrefix + "TOTAL_TIMEOUT": "forever-xyz"})
		if !errors.Is(err, ErrConfig) || !strings.Contains(err.Error(), EnvPrefix+"TOTAL_TIMEOUT") || strings.Contains(err.Error(), "forever-xyz") {
			t.Fatalf("invalid value: %v", err)
		}
		if _, err := ConfigFromEnvWith(Config{}, map[string]string{EnvPrefix + "URL": "ftp://nope"}); !errors.Is(err, ErrConfig) {
			t.Fatalf("invalid url: %v", err)
		}
		if _, err := ConfigFromEnvWith(Config{}, map[string]string{EnvPrefix + "ENDPOINT": "nope"}); !errors.Is(err, ErrConfig) {
			t.Fatalf("invalid endpoint: %v", err)
		}
		t.Setenv(EnvPrefix+"URL", "http://process-env:9")
		if cfg, err := ConfigFromEnv(); err != nil || cfg.URL != "http://process-env:9" {
			t.Fatalf("process env: %v %s", err, cfg.URL)
		}
	})

	t.Run("endpoint helpers", func(t *testing.T) {
		cases := map[string]string{
			"http://h:9411":                  "http://h:9411/api/v2/spans",
			"http://h:9411/":                 "http://h:9411/api/v2/spans",
			"https://gw/zipkin":              "https://gw/zipkin/api/v2/spans",
			"http://h:9411/api/v2/spans/":    "http://h:9411/api/v2/spans",
			"http://h:9411/api/v2/spans":     "http://h:9411/api/v2/spans",
			"https://gw/zipkin/api/v2/spans": "https://gw/zipkin/api/v2/spans",
		}
		for in, want := range cases {
			if got := SpansEndpoint(in); got != want {
				t.Errorf("SpansEndpoint(%q) = %q, want %q", in, got, want)
			}
		}
		if got := BaseFromEndpoint("https://gw/zipkin/api/v2/spans/"); got != "https://gw/zipkin" {
			t.Fatal(got)
		}
		if got := (Config{}).SpansEndpoint(); got != DefaultURL+SpansPath {
			t.Fatal(got)
		}
	})

	t.Run("core.redaction.config", func(t *testing.T) {
		cfg := Config{
			CommonConfig: CommonConfig{URL: "https://h", Headers: map[string]string{"Authorization": "Bearer hdr-xyz"}},
			Endpoint:     "https://user:pw-xyz@h/api/v2/spans",
		}
		forms := fmt.Sprintf("%v|%+v|%#v|%s", cfg, cfg, cfg, cfg.String())
		if strings.Contains(forms, "hdr-xyz") || strings.Contains(forms, "pw-xyz") || !strings.Contains(forms, Redacted) {
			t.Fatalf("forms: %s", forms)
		}
	})

	t.Run("invalid config", func(t *testing.T) {
		if _, err := NewClient(Config{CommonConfig: CommonConfig{URL: "mailto:x"}}); !errors.Is(err, ErrConfig) {
			t.Fatalf("NewClient: %v", err)
		}
		if _, err := NewClient(Config{CommonConfig: CommonConfig{TLS: TLSConfig{CACertPEM: "bad"}}}); !errors.Is(err, ErrConfig) {
			t.Fatalf("bad TLS: %v", err)
		}
		if _, err := NewClient(Config{Endpoint: "ftp://x"}); !errors.Is(err, ErrConfig) {
			t.Fatalf("bad endpoint: %v", err)
		}
	})
}

func TestClient(t *testing.T) {
	ts := fixtureServer(t, "health.ok", "serverVersion.ok", "listServices.ok")

	t.Run("health ping version", func(t *testing.T) {
		c := newTestClient(t, ts.URL, nil)
		hs, err := c.Health(t.Context())
		if err != nil || !hs.Healthy || hs.Status != "UP" || hs.Details["zipkin"] != "UP" || hs.Details["httpStatus"] != "200" {
			t.Fatalf("health %+v %v", hs, err)
		}
		if d, err := c.Ping(t.Context()); err != nil || d <= 0 {
			t.Fatalf("ping %s %v", d, err)
		}
		if v, err := c.ServerVersion(t.Context()); err != nil || v != "3.6.1" {
			t.Fatalf("version %q %v", v, err)
		}
		if c.Config().URL != ts.URL || c.Config().Endpoint != ts.URL+SpansPath {
			t.Fatalf("Config(): %s", c.Config())
		}
		if c.Query() == nil || c.Reporter() == nil || c.Reporter().Endpoint() != ts.URL+SpansPath {
			t.Fatal("modules")
		}
	})

	t.Run("health down", func(t *testing.T) {
		down := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte(`{"status":"DOWN","zipkin":{"status":"DOWN"}}`))
		})
		hs, err := newTestClient(t, down.URL, nil).Health(t.Context())
		if err != nil || hs.Healthy || hs.Status != "DOWN" || hs.Details["zipkin"] != "DOWN" {
			t.Fatalf("health %+v %v", hs, err)
		}
		empty := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) {})
		if hs, err := newTestClient(t, empty.URL, nil).Health(t.Context()); err != nil || !hs.Healthy || hs.Status != "UP" {
			t.Fatalf("empty body %+v %v", hs, err)
		}
		cancelled, cancel := context.WithCancel(t.Context())
		cancel()
		if _, err := newTestClient(t, empty.URL, nil).Health(cancelled); !errors.Is(err, ErrCancelled) {
			t.Fatalf("cancelled: %v", err)
		}
	})

	t.Run("gateway authentication", func(t *testing.T) {
		gw := newTestServer(t, func(w http.ResponseWriter, r *http.Request, _ int) {
			if r.Header.Get("Authorization") != "Bearer "+testToken {
				w.WriteHeader(http.StatusUnauthorized)
				_, _ = w.Write([]byte("bad token " + r.Header.Get("Authorization")))
				return
			}
			_, _ = w.Write([]byte(`[]`))
		})
		ok := newTestClient(t, gw.URL, func(c *Config) { c.Headers = map[string]string{"Authorization": "Bearer " + testToken} })
		if _, err := ok.Ping(t.Context()); err != nil {
			t.Fatal(err)
		}
		bad := newTestClient(t, gw.URL, func(c *Config) { c.Headers = map[string]string{"Authorization": "Bearer " + testToken + "x"} })
		_, err := bad.Ping(t.Context())
		if !errors.Is(err, ErrAuthentication) || strings.Contains(err.Error(), testToken) {
			t.Fatalf("bad token: %v", err)
		}
	})

	t.Run("server version errors", func(t *testing.T) {
		ts404 := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) {
			loadFixture(t, "deleteTrace.unsupported").write(w) // Zipkin's plain "404 Not Found"
		})
		if _, err := newTestClient(t, ts404.URL, nil).ServerVersion(t.Context()); !errors.Is(err, ErrUnsupported) {
			t.Fatalf("no version endpoint: %v", err)
		}
		tsEmpty := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) { _, _ = w.Write([]byte(`{}`)) })
		if _, err := newTestClient(t, tsEmpty.URL, nil).ServerVersion(t.Context()); !errors.Is(err, ErrUnsupported) {
			t.Fatalf("empty version: %v", err)
		}
		ts500 := newTestServer(t, status(http.StatusInternalServerError))
		c := newTestClient(t, ts500.URL, func(cfg *Config) { cfg.Retry = fastRetry(0) })
		if _, err := c.ServerVersion(t.Context()); !errors.Is(err, ErrUnavailable) {
			t.Fatalf("version 500: %v", err)
		}
		if _, err := c.Ping(t.Context()); !errors.Is(err, ErrUnavailable) {
			t.Fatalf("ping 500: %v", err)
		}
		tsBad := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) { _, _ = w.Write([]byte("nope")) })
		if _, err := newTestClient(t, tsBad.URL, nil).ServerVersion(t.Context()); !errors.Is(err, ErrUnavailable) {
			t.Fatalf("version decode: %v", err)
		}
	})

	t.Run("zipkin text errors reach the message", func(t *testing.T) {
		bad := fixtureServer(t, "listSpans.missing400")
		c := newTestClient(t, bad.URL, nil)
		_, err := c.transport.do(t.Context(), &request{Method: http.MethodGet, Path: "/api/v2/spans", Operation: "zipkin.listSpans"})
		if !errors.Is(err, ErrValidation) || !strings.Contains(err.Error(), "Mandatory parameter/header/attribute is missing: serviceName") {
			t.Fatalf("err: %v", err)
		}
	})

	t.Run("core.close.idempotent", func(t *testing.T) {
		c := newTestClient(t, ts.URL, nil)
		first, second := c.Close(), c.Close()
		if first != nil || second != nil {
			t.Fatal("close")
		}
		if _, err := c.Ping(t.Context()); !errors.Is(err, ErrCancelled) {
			t.Fatalf("after close: %v", err)
		}
		if err := c.Reporter().Report(t.Context(), []ZipkinSpan{{TraceID: "463ac35cad1c0df6", ID: "00000000000000b1"}}); !errors.Is(err, ErrCancelled) {
			t.Fatalf("report after close: %v", err)
		}
	})

	t.Run("core.health.unreachable", func(t *testing.T) {
		c := newTestClient(t, "http://"+closedAddr(t), func(cfg *Config) { cfg.ConnectTimeout = time.Second })
		if hs, err := c.Health(t.Context()); err != nil || hs.Healthy || hs.Status != "unreachable" {
			t.Fatalf("hs=%+v err=%v", hs, err)
		}
	})

	t.Run("from env", func(t *testing.T) {
		t.Setenv(EnvPrefix+"URL", ts.URL)
		c, err := NewClientFromEnv()
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = c.Close() }()
		if _, err := c.Ping(t.Context()); err != nil {
			t.Fatal(err)
		}
		t.Setenv(EnvPrefix+"URL", "mailto:x")
		if _, err := NewClientFromEnv(); !errors.Is(err, ErrConfig) {
			t.Fatalf("bad env: %v", err)
		}
	})
}
