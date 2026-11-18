module github.com/grow-codes-packages/zipkin/go

go 1.26

// Dependencies are added per category by sdk-kit; "make deps" runs "go mod tidy"
// to complete the require list and write go.sum.

require go.opentelemetry.io/otel v1.47.0

require go.opentelemetry.io/otel/trace v1.47.0
require (
	go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp v0.72.0
	go.opentelemetry.io/contrib/propagators/b3 v1.47.0
	go.opentelemetry.io/otel/exporters/otlp/otlptrace v1.47.0
	go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc v1.47.0
	go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp v1.47.0
	go.opentelemetry.io/otel/sdk v1.47.0
	google.golang.org/grpc v1.85.0
)
