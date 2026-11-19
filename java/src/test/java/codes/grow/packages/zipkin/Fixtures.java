package codes.grow.packages.zipkin;

import codes.grow.packages.zipkin.core.Json;
import codes.grow.packages.zipkin.core.MockServer;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** Recorded responses of the real Zipkin 3.6.1 (prep fixtures), replayed by {@link MockServer}. */
final class Fixtures {
  private Fixtures() {}

  static JsonNode load(String name) {
    try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name + ".json")) {
      if (in == null) {
        throw new IllegalArgumentException("no fixture " + name);
      }
      return Json.tree(in.readAllBytes());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The recorded response body as text (JSON re-encoded, text as is). */
  static String body(String name) {
    JsonNode body = load(name).path("response").path("body");
    return body.isTextual() ? body.asText() : body.isNull() ? "" : body.toString();
  }

  /** The recorded request body (JSON). */
  static JsonNode requestBody(String name) {
    return load(name).path("request").path("body");
  }

  /** Queues the recorded response (status, content type, body). */
  static MockServer.Responder responder(String name) {
    JsonNode res = load(name).path("response");
    int status = res.path("status").asInt();
    String body = body(name);
    return ex -> {
      for (JsonNode h : res.path("headers")) {
        String line = h.asText();
        int colon = line.indexOf(':');
        ex.getResponseHeaders()
            .add(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
      }
      MockServer.send(ex, status, body);
    };
  }

  static void enqueue(MockServer server, String... names) {
    for (String n : names) {
      server.enqueue(responder(n));
    }
  }
}
