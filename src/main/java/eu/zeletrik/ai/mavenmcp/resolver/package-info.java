/**
 * Resolution module: the primary {@code ArtifactRepository} the tools consume. Aggregates the
 * ordered artifact backends and applies precedence — for each lookup it consults backends in order
 * (Maven Central first, then GitLab) and returns the first that has the artifact (failover).
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"artifact"})
package eu.zeletrik.ai.mavenmcp.resolver;
