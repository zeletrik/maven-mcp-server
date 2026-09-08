package eu.zeletrik.ai.mavenmcp.gradleplugins

import eu.zeletrik.ai.mavenmcp.artifact.ArtifactBackend
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactFile
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactMatch
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactMetadata
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactResult
import eu.zeletrik.ai.mavenmcp.artifact.Coordinates
import eu.zeletrik.ai.mavenmcp.mavenrepo.MavenHttpClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Repository

/** The portal is consulted last: it only adds coordinates the other backends do not carry. */
private const val PORTAL_PRECEDENCE = 20

/**
 * Gradle Plugin Portal backend (lowest precedence). The portal is a plain Maven-layout repository,
 * so version and file access delegate to the shared [MavenHttpClient] with no auth headers.
 *
 * It exists to cover Gradle plugin **marker** artifacts — a plugin id `org.sonarqube` publishes as
 * `org.sonarqube:org.sonarqube.gradle.plugin`, which Maven Central does not host at all (a plugin's
 * implementation artifact may also be on Central, but often lags or stops being published there).
 * The marker POM carries a single dependency naming the implementation coordinates.
 *
 * The portal also FRONTS Maven Central: any coordinate it does not host itself answers `303 See
 * Other` pointing at repo.maven.apache.org, with an empty body. Passing `redirectMeansNotFound` maps
 * that to not-found, which matters for more than tidiness — [MavenHttpClient] would otherwise report
 * the empty body as a source error, and the composite's failover deliberately ranks a source error
 * ABOVE a definitive not-found, so every coordinate absent everywhere would degrade from
 * `not_found` to `source_error`. Following the redirect instead is declined: it would let a remote
 * response steer the client off the configured base URL, which is exactly what validating and
 * encoding every path segment exists to prevent.
 *
 * The portal exposes no keyword-search API (only an HTML page), so [search] contributes nothing.
 */
@Repository
@Order(PORTAL_PRECEDENCE)
@ConditionalOnProperty(
    prefix = "gradle-plugin-portal",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class GradlePluginPortalArtifactRepository(
    private val client: MavenHttpClient,
    private val properties: GradlePluginPortalProperties,
) : ArtifactBackend {

    override suspend fun fetchVersions(coordinates: Coordinates): ArtifactResult<ArtifactMetadata> =
        client.fetchVersions(
            properties.baseUrl,
            NO_HEADERS,
            coordinates,
            properties.timeout,
            redirectMeansNotFound = true,
        )

    override suspend fun fetchFile(
        coordinates: Coordinates,
        version: String,
        file: ArtifactFile,
    ): ArtifactResult<String> =
        client.fetchArtifactFile(
            properties.baseUrl,
            NO_HEADERS,
            coordinates,
            version,
            file,
            properties.timeout,
            redirectMeansNotFound = true,
        )

    /** The portal has no search endpoint; an empty success keeps the aggregate search unaffected. */
    override suspend fun search(query: String, limit: Int): ArtifactResult<List<ArtifactMatch>> =
        ArtifactResult.Success(emptyList())

    private companion object {
        val NO_HEADERS = emptyMap<String, String>()
    }
}
