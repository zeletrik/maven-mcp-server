package eu.zeletrik.ai.mavenmcp.gitlab

import eu.zeletrik.ai.mavenmcp.artifact.ArtifactBackend
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactFile
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactMatch
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactMetadata
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactResult
import eu.zeletrik.ai.mavenmcp.artifact.Coordinates
import eu.zeletrik.ai.mavenmcp.artifact.VersionResolver
import eu.zeletrik.ai.mavenmcp.mavenrepo.MavenHttpClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Repository
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/** GitLab is consulted after Maven Central (higher [Order] value = lower precedence). */
private const val GITLAB_PRECEDENCE = 10

/** GitLab caps page size at 100; fetch a full page and dedupe to distinct packages client-side. */
private const val PACKAGES_PAGE_SIZE = 100

/**
 * GitLab Maven registry backend (precedence after Central). Active only when `gitlab.enabled=true`.
 * Version/POM access is delegated to the shared [MavenHttpClient] with the configured base URL and
 * auth header. Search uses the GitLab Packages API (distinct endpoint) to find internal Maven
 * artifacts; when no packages API URL can be resolved, search contributes nothing.
 */
// See MavenHttpClient: a broad catch is how parse failures become SourceError instead of throwing.
@Suppress("TooGenericExceptionCaught")
@Repository
@Order(GITLAB_PRECEDENCE)
@ConditionalOnProperty(prefix = "gitlab", name = ["enabled"], havingValue = "true")
class GitLabArtifactRepository(
    private val client: MavenHttpClient,
    private val properties: GitLabProperties,
    private val versionResolver: VersionResolver,
) : ArtifactBackend {

    private val jsonMapper: JsonMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    override suspend fun fetchVersions(coordinates: Coordinates): ArtifactResult<ArtifactMetadata> =
        client.fetchVersions(properties.baseUrl, properties.authHeaders(), coordinates, properties.timeout)

    override suspend fun fetchFile(
        coordinates: Coordinates,
        version: String,
        file: ArtifactFile,
    ): ArtifactResult<String> =
        client.fetchArtifactFile(
            properties.baseUrl, properties.authHeaders(), coordinates, version, file, properties.timeout,
        )

    override suspend fun search(query: String, limit: Int): ArtifactResult<List<ArtifactMatch>> {
        val searchUrl = properties.effectiveSearchUrl()
        if (searchUrl.isBlank()) return ArtifactResult.Success(emptyList())
        val uri = UriComponentsBuilder.fromUriString(searchUrl)
            .queryParam("package_type", "maven")
            .queryParam("package_name", query)
            .queryParam("per_page", PACKAGES_PAGE_SIZE)
            .build()
            .encode()
            .toUri()
        val text = client.fetchText(
            uri,
            properties.authHeaders(),
            notFoundMessage = "GitLab packages not found",
            label = "gitlab search '$query'",
            timeout = properties.timeout,
        )
        return when (text) {
            is ArtifactResult.Success -> parsePackages(text.value, query, limit)
            // A missing packages endpoint / no access should not fail the aggregate search.
            is ArtifactResult.NotFound -> ArtifactResult.Success(emptyList())
            is ArtifactResult.SourceError -> text
            is ArtifactResult.ValidationError -> text
        }
    }

    private fun parsePackages(json: String, query: String, limit: Int): ArtifactResult<List<ArtifactMatch>> =
        try {
            // groupBy keeps encounter order, so GitLab's own package ordering survives into results.
            val versionsByCoordinates = jsonMapper.readValue(json, Array<GitLabPackage>::class.java)
                .mapNotNull(::coordinatesAndVersion)
                .groupBy({ it.first }, { it.second })
            val matches = versionsByCoordinates.entries.take(limit).map { (coordinates, versions) ->
                val latest = versionResolver.latestStable(versions) ?: versionResolver.latest(versions)
                ArtifactMatch(coordinates.groupId, coordinates.artifactId, latest)
            }
            ArtifactResult.Success(matches)
        } catch (e: Exception) {
            ArtifactResult.SourceError("Unparseable GitLab packages response for '$query': ${e.message}")
        }

    /** Usable only with a name, a version, and a name that actually splits into coordinates. */
    private fun coordinatesAndVersion(pkg: GitLabPackage): Pair<Coordinates, String>? {
        val name = pkg.name ?: return null
        val version = pkg.version ?: return null
        return splitPackageName(name)?.let { it to version }
    }

    /** `com/example/.../artifact` → groupId `com.example.…`, artifactId `artifact`. */
    private fun splitPackageName(name: String): Coordinates? {
        val separator = name.lastIndexOf('/')
        if (separator <= 0 || separator == name.lastIndex) return null
        return Coordinates(name.substring(0, separator).replace('/', '.'), name.substring(separator + 1))
    }
}
