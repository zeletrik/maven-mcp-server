/**
 * GitLab Maven package registry backend (lower precedence than Central). Opt-in via configuration;
 * resolves versions/POMs from a configured GitLab Maven base URL with an auth header, via the
 * shared {@code MavenHttpClient}. GitLab has no keyword-search endpoint, so search is a no-op here.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"artifact", "mavenrepo"})
package eu.zeletrik.ai.mavenmcp.gitlab;
