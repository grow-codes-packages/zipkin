package integration

import (
	"context"
	"strings"
	"testing"

	zipkin "github.com/grow-codes-packages/zipkin/go"
	"github.com/grow-codes-packages/zipkin/go/testkit"
)

// capabilities the deployment under test offers (see conformance "requires").
// Every capability left out must be justified in docs/compatibility.md.
var capabilities = []string{"version"}

// categories runs "core" plus this service's categories.
func categories() []string {
	return append([]string{"core"}, strings.Split("tracing", ",")...)
}

// factories builds the conformance targets. Service code wires its category
// implementations here (for example f["secrets"] = testkit.SecretsFactory(...)).
// Categories without a factory are reported as skipped.
func factories(t *testing.T) testkit.Factories {
	t.Helper()
	return testkit.Factories{
		"core": testkit.CoreFactory(func(context.Context) (testkit.CoreTarget, error) {
			return zipkin.NewClientFromEnv()
		}),
	}
}
