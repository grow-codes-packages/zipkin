// Created by sdk-kit 0.1.0; owned by this repository.
package codes.grow.packages.zipkin.examples;

import codes.grow.packages.zipkin.ZipkinClient;
import codes.grow.packages.zipkin.core.AuthenticationException;
import codes.grow.packages.zipkin.core.HealthStatus;
import codes.grow.packages.zipkin.core.UnsupportedException;

/** Basics: configuration from the environment, health, ping, server version and errors. */
public final class BasicExample {
  private BasicExample() {}

  /**
   * Runs the example.
   *
   * @param args unused
   */
  public static void main(String[] args) {
    // Reads GROW_ZIPKIN_URL, GROW_ZIPKIN_TOKEN, timeouts, TLS and proxy settings.
    try (var client = ZipkinClient.fromEnv()) {
      HealthStatus health = client.health(); // never throws for an unreachable service
      System.out.println("health: " + health);
      if (!health.healthy()) {
        return;
      }
      try {
        System.out.println("ping: " + client.ping().toMillis() + " ms");
      } catch (AuthenticationException e) {
        // messages never contain the token
        System.out.println("bad credentials: " + e.getMessage());
      }
      try {
        System.out.println("server version: " + client.serverVersion());
      } catch (UnsupportedException e) {
        System.out.println("the server exposes no version");
      }
    }
  }
}
