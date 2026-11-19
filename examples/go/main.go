// Command example shows the Zipkin Go SDK against a real server: configuration from
// the environment, health, reporting spans without OpenTelemetry, querying them back,
// and OpenTelemetry tracing with the native Zipkin exporter.
//
//	GROW_ZIPKIN_URL=http://127.0.0.1:9411 go run .
package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"time"

	zipkin "github.com/grow-codes-packages/zipkin/go"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func randomHex(n int) string {
	b := make([]byte, n/2)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func run() error {
	cfg, err := zipkin.ConfigFromEnv()
	if err != nil {
		return err // CONFIG error naming the variable, never its value
	}
	cfg.Logger = slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelWarn}))
	fmt.Println("config:", cfg) // gateway credentials in Headers print as [REDACTED]

	client, err := zipkin.NewClient(cfg)
	if err != nil {
		return err
	}
	defer func() { _ = client.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	health, err := client.Health(ctx)
	if err != nil {
		return err
	}
	fmt.Printf("health: healthy=%t status=%s\n", health.Healthy, health.Status)
	version, err := client.ServerVersion(ctx)
	switch {
	case errors.Is(err, zipkin.ErrUnsupported):
		fmt.Println("server version: not exposed")
	case err != nil:
		return err
	default:
		fmt.Println("server version:", version)
	}

	// 1. Report spans directly (no OpenTelemetry).
	traceID := randomHex(32)
	now := time.Now().UnixMicro()
	err = client.Reporter().Report(ctx, []zipkin.ZipkinSpan{
		{
			TraceID: traceID, ID: "0000000000000001", Kind: zipkin.KindServer, Name: "get /checkout",
			Timestamp: now, Duration: 120_000,
			LocalEndpoint: &zipkin.Endpoint{ServiceName: "example-frontend"},
			Tags:          map[string]string{"http.method": "GET"},
		},
		{
			TraceID: traceID, ParentID: "0000000000000001", ID: "0000000000000002", Kind: zipkin.KindClient, Name: "charge",
			Timestamp: now + 10_000, Duration: 80_000,
			LocalEndpoint:  &zipkin.Endpoint{ServiceName: "example-frontend"},
			RemoteEndpoint: &zipkin.Endpoint{ServiceName: "example-payments"},
		},
	})
	if err != nil {
		return err
	}
	trace, err := client.Query().WaitForTrace(ctx, traceID, zipkin.WaitForTraceOptions{MinSpans: 2})
	if err != nil {
		return err
	}
	fmt.Printf("reported trace %s: %d spans\n", trace.TraceID, len(trace.Spans))

	// 2. OpenTelemetry tracing with the native Zipkin v2 JSON exporter.
	h, err := zipkin.InitTracing(ctx, zipkin.TracingConfig{ServiceName: "example-otel", DisableSetGlobal: true})
	if err != nil {
		return err
	}
	var otelTrace string
	err = zipkin.Traced(ctx, "process order", func(ctx context.Context) error {
		otelTrace, _ = zipkin.CurrentTraceID(ctx)
		zipkin.AddEvent(ctx, "validated", map[string]any{"items": 3})
		return nil
	}, zipkin.WithTracer(h.Tracer("example")))
	if err != nil {
		return err
	}
	if err := h.Shutdown(ctx); err != nil { // flushes
		return err
	}
	if _, err := client.Query().WaitForTrace(ctx, otelTrace, zipkin.WaitForTraceOptions{}); err != nil {
		return err
	}

	// 3. Query.
	services, err := client.Query().ListServices(ctx)
	if err != nil {
		return err
	}
	fmt.Println("services:", services)
	summaries, err := client.Query().SearchTraces(ctx, zipkin.TraceSearchQuery{Service: "example-frontend", Limit: 5})
	if err != nil {
		return err
	}
	for _, s := range summaries {
		fmt.Printf("trace %s root=%s spans=%d duration=%dµs\n", s.TraceID, s.RootName, s.SpanCount, s.DurationMicros)
	}
	links, err := client.Query().GetDependenciesAt(ctx, time.Now(), time.Hour)
	if err != nil {
		return err
	}
	for _, l := range links {
		fmt.Printf("dependency %s -> %s calls=%d errors=%d\n", l.Parent, l.Child, l.CallCount, l.ErrorCount)
	}
	return nil
}
