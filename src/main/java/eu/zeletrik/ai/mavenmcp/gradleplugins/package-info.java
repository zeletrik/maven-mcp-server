/**
 * Gradle Plugin Portal backend (lowest precedence). Resolves Gradle plugin marker artifacts
 * ({@code <plugin-id>:<plugin-id>.gradle.plugin}), which Maven Central does not host, from
 * {@code https://plugins.gradle.org/m2/} via the shared {@code MavenHttpClient}. The portal has no
 * keyword-search API, so search contributes nothing here.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"artifact", "mavenrepo"})
package eu.zeletrik.ai.mavenmcp.gradleplugins;
