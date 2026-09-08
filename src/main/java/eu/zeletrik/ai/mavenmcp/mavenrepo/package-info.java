/**
 * Shared Maven-repository HTTP client: fetches and parses maven-metadata.xml and raw POMs from any
 * Maven-layout repository (Central, GitLab, future Nexus, …) given a base URL and optional auth
 * headers. Reused by the concrete backend adapters so the transport/parse logic lives in one place.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"artifact"})
package eu.zeletrik.ai.mavenmcp.mavenrepo;
