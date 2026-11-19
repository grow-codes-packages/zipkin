package codes.grow.packages.zipkin;

import java.util.Optional;

/**
 * Build information of the server ({@code GET /info}).
 *
 * @param version server version, for example {@code 3.6.1}
 * @param commit short git commit
 */
public record ZipkinServerInfo(Optional<String> version, Optional<String> commit) {
  /**
   * Normalises nulls.
   *
   * @param version version
   * @param commit commit
   */
  public ZipkinServerInfo {
    version = version == null ? Optional.empty() : version;
    commit = commit == null ? Optional.empty() : commit;
  }
}
