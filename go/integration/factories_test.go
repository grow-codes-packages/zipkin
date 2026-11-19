package integration

import (
	"context"
	"strings"
	"testing"

	zipkin "github.com/grow-codes-packages/zipkin/go"
	"github.com/grow-codes-packages/zipkin/go/testkit"
)

// capabilities the deployment under test offers (see conformance "requires").
// Zipkin exposes its version (/info), a query API with listServices and a
// dependency graph. Nothing is skipped.
var capabilities = []string{"version", "query", "services", "dependencies"}

// categories runs "core" plus this service's categories.
func categories() []string {
	return append([]string{"core"}, strings.Split("tracing", ",")...)
}

// factories builds the conformance targets: the client for "core", and for
// "tracing" InitTracing (native Zipkin v2 JSON exporter) plus the client's query
// API. In-memory Zipkin makes spans queryable as soon as they are accepted, so no
// warm-up is needed before listServices.
func factories(t *testing.T) testkit.Factories {
	t.Helper()
	return testkit.Factories{
		"core": testkit.CoreFactory(func(context.Context) (testkit.CoreTarget, error) {
			return zipkin.NewClientFromEnv()
		}),
		"tracing": testkit.TracingFactory(func(ctx context.Context, serviceName string) (testkit.TracingTarget, error) {
			client, err := zipkin.NewClientFromEnv()
			if err != nil {
				return testkit.TracingTarget{}, err
			}
			t.Cleanup(func() { _ = client.Close() })
			h, err := zipkin.InitTracing(ctx, zipkin.TracingConfig{ServiceName: serviceName, DisableSetGlobal: true})
			if err != nil {
				return testkit.TracingTarget{}, err
			}
			return testkit.TracingTarget{Handle: h, Query: client.Query()}, nil
		}),
	}
}
