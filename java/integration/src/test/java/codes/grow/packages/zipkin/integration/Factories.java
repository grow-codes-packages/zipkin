package codes.grow.packages.zipkin.integration;

import codes.grow.packages.zipkin.ZipkinClient;
import codes.grow.packages.zipkin.core.EnvReader;
import codes.grow.packages.zipkin.testing.ConformanceRunner;

/**
 * Conformance targets of this service (owned by the service repository; generated once).
 *
 * <p>Register one target per category: {@code core} is the client; add {@code secrets}, {@code
 * logging} or {@code tracing} targets as the service implements them. Categories without a target
 * do not run. Every capability left out of {@link #CAPABILITIES} must be justified in {@code
 * docs/compatibility.md}.
 */
final class Factories {
  /** Capabilities the deployment under test offers (see the scenario {@code requires}). */
  static final String[] CAPABILITIES = {"version"};

  private Factories() {}

  static void register(ConformanceRunner.Builder runner, EnvReader env) {
    runner.target("core", scenario -> ZipkinClient.builder().fromEnv(env).build());
  }
}
