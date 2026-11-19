// Package zipkin is the Grow Codes SDK for Zipkin: the v2 query API, a span reporter
// for applications without OpenTelemetry, and OpenTelemetry tracing that exports
// Zipkin v2 JSON.
//
// Create a client from the environment (GROW_ZIPKIN_URL, ZIPKIN_URL,
// OTEL_EXPORTER_ZIPKIN_ENDPOINT, ...) or from a Config literal:
//
//	client, err := zipkin.NewClientFromEnv()
//	if err != nil {
//		return err
//	}
//	defer client.Close()
//	status, err := client.Health(ctx)
//	services, err := client.Query().ListServices(ctx)
//	err = client.Reporter().Report(ctx, []zipkin.ZipkinSpan{...})
//
// Tracing with OpenTelemetry (native Zipkin v2 JSON exporter, W3C + B3 propagation):
//
//	h, err := zipkin.InitTracing(ctx, zipkin.TracingConfig{ServiceName: "checkout"})
//	if err != nil {
//		return err
//	}
//	defer h.Shutdown(ctx)
//	err = zipkin.Traced(ctx, "charge", func(ctx context.Context) error { return nil })
//
// Stock Zipkin has no authentication; behind a gateway put the credentials in
// Config.Headers or TracingConfig.Headers (they are redacted everywhere).
//
// Every error is an *Error; compare codes with errors.Is against the Err*
// sentinels (errors.Is(err, zipkin.ErrValidation)). Credentials never appear in
// errors, hooks or logs.
//
// Zipkin is a trademark of The OpenZipkin authors. This package is not affiliated
// with or endorsed by The OpenZipkin authors.
package zipkin
