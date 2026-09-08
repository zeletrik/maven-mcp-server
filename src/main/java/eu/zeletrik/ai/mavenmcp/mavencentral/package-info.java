/**
 * Adapter module: {@code MavenCentralArtifactRepository} (WebClient), Jackson XML metadata DTOs,
 * and {@code MavenCentralProperties}. The always-available backend, consulted first. Declared as an
 * explicit application module so the boundary is verified by the build.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"artifact", "mavenrepo"})
package eu.zeletrik.ai.mavenmcp.mavencentral;
