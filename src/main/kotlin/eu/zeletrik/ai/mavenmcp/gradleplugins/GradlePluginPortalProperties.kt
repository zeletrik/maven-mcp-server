package eu.zeletrik.ai.mavenmcp.gradleplugins

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Gradle Plugin Portal configuration. Unlike the GitLab backend this needs no credentials — the
 * portal is public — so it is enabled by default; set `enabled: false` to drop it from resolution.
 *
 * @property enabled whether the portal participates in resolution.
 * @property baseUrl the portal's Maven-layout base URL (must end with '/').
 * @property timeout bounded connect + response timeout for portal requests.
 */
private const val DEFAULT_TIMEOUT_SECONDS = 5L

@ConfigurationProperties(prefix = "gradle-plugin-portal")
data class GradlePluginPortalProperties(
    val enabled: Boolean = true,
    val baseUrl: String = "https://plugins.gradle.org/m2/",
    val timeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
)
