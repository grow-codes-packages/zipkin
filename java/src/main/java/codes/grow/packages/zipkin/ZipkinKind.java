package codes.grow.packages.zipkin;

import codes.grow.packages.zipkin.core.ValidationException;
import java.util.Locale;

/** Zipkin span kind. A span without a kind is internal (local) work. */
public enum ZipkinKind {
  /** Client side of an RPC. */
  CLIENT,
  /** Server side of an RPC. */
  SERVER,
  /** Sends a message to a broker. */
  PRODUCER,
  /** Receives a message from a broker. */
  CONSUMER;

  /**
   * Parses a kind case-insensitively ({@code "client"} → {@link #CLIENT}).
   *
   * @param text kind name
   * @return the kind
   * @throws ValidationException when the text is not one of the four kinds
   */
  public static ZipkinKind parse(String text) {
    if (text != null) {
      String t = text.trim().toUpperCase(Locale.ROOT);
      for (ZipkinKind k : values()) {
        if (k.name().equals(t)) {
          return k;
        }
      }
    }
    throw new ValidationException("zipkin: kind must be CLIENT, SERVER, PRODUCER or CONSUMER");
  }
}
