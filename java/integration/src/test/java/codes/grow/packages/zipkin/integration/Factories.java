package codes.grow.packages.zipkin.integration;

import codes.grow.packages.zipkin.ZipkinClient;
import codes.grow.packages.zipkin.ZipkinTracing;
import codes.grow.packages.zipkin.ZipkinTracingHandle;
import codes.grow.packages.zipkin.core.EnvReader;
import codes.grow.packages.zipkin.testing.ConformanceRunner;
import codes.grow.packages.zipkin.testing.tracing.TracingTarget;
import codes.grow.packages.zipkin.tracing.TracingConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;

/**
 * Conformance targets of this service: {@code core} is the client, {@code tracing} exports Zipkin
 * v2 JSON through the package's own exporter (the Zipkin preset from the environment) and reads
 * back through the query client. Zipkin serves every tracing capability: the query API, services
 * and dependencies. In-memory storage makes a reported span queryable at once, so no warm-up is
 * needed before {@code listServices}.
 */
final class Factories {
  /** Capabilities the deployment under test offers (see the scenario {@code requires}). */
  static final String[] CAPABILITIES = {"version", "query", "services", "dependencies"};

  /** Service name of the tracing scenarios (Zipkin lower-cases names). */
  static final String SERVICE_NAME = "grow-conformance-java";

  private Factories() {}

  static void register(ConformanceRunner.Builder runner, EnvReader env) {
    Map<String, String> vars;
    try {
      vars = ConformanceTest.environment();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    runner.serviceName(SERVICE_NAME);
    runner.target("core", scenario -> ZipkinClient.builder().fromEnv(env).build());
    runner.target(
        "tracing",
        scenario -> {
          ZipkinClient client = ZipkinClient.builder().fromEnv(env).build();
          TracingConfig config =
              TracingConfig.builder(SERVICE_NAME)
                  .setGlobal(false)
                  .batch(
                      new TracingConfig.BatchSettings(
                          2048, 512, Duration.ofMillis(100), Duration.ofSeconds(30)))
                  .build();
          ZipkinTracingHandle handle =
              client.initTracing(config, ZipkinTracing.Options.builder().env(vars));
          return TracingTarget.of(handle.handle(), client.query());
        });
  }
}
