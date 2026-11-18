// Created by sdk-kit 0.1.0; owned by this repository.
package codes.grow.packages.zipkin.examples;

import codes.grow.packages.zipkin.ZipkinClient;
import codes.grow.packages.zipkin.core.HealthStatus;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** The {@code …Async} methods: run calls concurrently and cancel a slow one. */
public final class AsyncExample {
  private AsyncExample() {}

  /**
   * Runs the example.
   *
   * @param args unused
   */
  public static void main(String[] args) {
    try (var client = ZipkinClient.fromEnv()) {
      CompletableFuture<HealthStatus> health = client.healthAsync();
      CompletableFuture<Duration> ping = client.pingAsync();
      CompletableFuture.allOf(health, ping.exceptionally(e -> null)).join();
      System.out.println("health: " + health.join());
      ping.whenComplete(
          (latency, error) ->
              System.out.println(error == null ? "ping: " + latency : "ping failed: " + error));

      // Cancelling a future aborts the HTTP exchange and any pending retry.
      CompletableFuture<String> version = client.serverVersionAsync();
      version.cancel(true);
      System.out.println("version cancelled: " + version.isCancelled());
    }
  }
}
