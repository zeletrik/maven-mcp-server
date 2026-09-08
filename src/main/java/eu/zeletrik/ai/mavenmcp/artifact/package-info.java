/**
 * Domain + port module: the {@code ArtifactRepository} port, domain models, the pure
 * {@code VersionResolver}, and the sealed result type. Framework-independent — its only
 * third-party dependency is maven-artifact, with no Spring, WebFlux or Jackson coupling. Declared
 * as an explicit Spring Modulith application module so the boundary is verified by the build.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {})
package eu.zeletrik.ai.mavenmcp.artifact;
