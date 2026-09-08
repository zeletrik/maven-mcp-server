package eu.zeletrik.ai.mavenmcp.mavencentral

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Externalized Maven Central endpoints and the outbound request timeout. Base URLs are read from
 * configuration rather than hardcoded, which keeps the source swappable (a mirror, or a
 * MockWebServer in tests) and gives the fixed-base-URL guard a single place to point at.
 *
 * @property metadataBaseUrl base for maven-metadata.xml and POM resources (must end with '/').
 * @property searchBaseUrl full URL of the Solr search endpoint.
 * @property timeout bounded connect + response timeout applied to every Maven Central request.
 */
private const val DEFAULT_TIMEOUT_SECONDS = 5L

@ConfigurationProperties(prefix = "maven-central")
data class MavenCentralProperties(
    val metadataBaseUrl: String = "https://repo1.maven.org/maven2/",
    val searchBaseUrl: String = "https://search.maven.org/solrsearch/select",
    val timeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
)
