package codes.grow.packages.zipkin;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Network endpoint of a span ({@code localEndpoint} / {@code remoteEndpoint}).
 *
 * @param serviceName service name (the server lower-cases it on ingest)
 * @param ipv4 IPv4 address
 * @param ipv6 IPv6 address
 * @param port port (1..65535)
 */
public record ZipkinEndpoint(
    Optional<String> serviceName, Optional<String> ipv4, Optional<String> ipv6, OptionalInt port) {
  /**
   * Normalises nulls and empty strings to empty.
   *
   * @param serviceName service name
   * @param ipv4 IPv4 address
   * @param ipv6 IPv6 address
   * @param port port
   */
  public ZipkinEndpoint {
    serviceName = nonEmpty(serviceName);
    ipv4 = nonEmpty(ipv4);
    ipv6 = nonEmpty(ipv6);
    port = port == null ? OptionalInt.empty() : port;
  }

  private static Optional<String> nonEmpty(Optional<String> v) {
    return v == null ? Optional.empty() : v.filter(s -> !s.isEmpty());
  }

  /**
   * An endpoint with only a service name.
   *
   * @param serviceName service name
   * @return the endpoint
   */
  public static ZipkinEndpoint of(String serviceName) {
    return of(serviceName, null, null, null);
  }

  /**
   * An endpoint from nullable parts.
   *
   * @param serviceName service name or {@code null}
   * @param ipv4 IPv4 address or {@code null}
   * @param ipv6 IPv6 address or {@code null}
   * @param port port or {@code null} (0 counts as unset)
   * @return the endpoint
   */
  public static ZipkinEndpoint of(String serviceName, String ipv4, String ipv6, Integer port) {
    return new ZipkinEndpoint(
        Optional.ofNullable(serviceName),
        Optional.ofNullable(ipv4),
        Optional.ofNullable(ipv6),
        port == null || port == 0 ? OptionalInt.empty() : OptionalInt.of(port));
  }

  /**
   * Whether no field is set (such an endpoint is omitted from the JSON).
   *
   * @return true when empty
   */
  public boolean isEmpty() {
    return serviceName.isEmpty() && ipv4.isEmpty() && ipv6.isEmpty() && port.isEmpty();
  }
}
