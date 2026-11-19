package codes.grow.packages.zipkin;

import codes.grow.packages.zipkin.tracing.query.DependencyLink;

/**
 * A service dependency edge with Zipkin's {@code errorCount} (0 when the server omits it).
 *
 * @param parent calling service
 * @param child called service
 * @param callCount calls in the window
 * @param errorCount failed calls in the window
 */
public record ZipkinDependencyLink(String parent, String child, long callCount, long errorCount) {
  /**
   * The category model without {@code errorCount}.
   *
   * @return the link
   */
  public DependencyLink toDependencyLink() {
    return new DependencyLink(parent, child, callCount);
  }
}
