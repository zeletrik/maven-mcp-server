package eu.zeletrik.ai.mavenmcp.tools

import eu.zeletrik.ai.mavenmcp.artifact.ArtifactFile
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactRepository
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactResult
import eu.zeletrik.ai.mavenmcp.artifact.Coordinates
import eu.zeletrik.ai.mavenmcp.artifact.VersionResolver
import kotlinx.coroutines.reactor.mono
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/**
 * The MCP tool surface. Each tool is a NON-suspend `@McpTool` method returning `Mono<T>`, because
 * Spring AI silently ignores `suspend` tool methods — the coroutine chain is therefore bridged to
 * Reactor with a [mono] builder at exactly this boundary, and nowhere deeper.
 *
 * Every tool body is a single builder wrapping the suspend repository or resolver call, with one
 * try/catch turning any escaping Throwable into a source-error result. Coordinates are validated
 * inside the builder before any repository call, error variants are conveyed as data rather than
 * thrown, and a failed result carries no partial payload — half an answer presented as an answer is
 * worse than a clean error.
 *
 * Depends only on the artifact port and the resolver, never on a concrete backend.
 */
// Catching Exception is the contract here, not an oversight: nothing may cross the tool boundary as
// a thrown error, so every escaping Throwable is mapped to a source-error result instead.
@Suppress("TooGenericExceptionCaught")
@Component
class MavenArtifactTools(
    private val repository: ArtifactRepository,
    private val versionResolver: VersionResolver,
) {

    @McpTool(
        name = "get_latest_version",
        description = "Return the latest version of a Maven artifact. By default returns the latest " +
                "STABLE release (excludes alpha/beta/RC/milestone/snapshot); set prerelease=true to " +
                "include pre-releases. Provide the Maven groupId and artifactId. For a GRADLE PLUGIN, " +
                "use its marker coordinates — groupId is the plugin id and artifactId is the plugin id " +
                "with '.gradle.plugin' appended (plugin 'org.sonarqube' -> groupId 'org.sonarqube', " +
                "artifactId 'org.sonarqube.gradle.plugin'); those resolve from the Gradle Plugin Portal, " +
                "which often has releases Maven Central does not.",
        annotations = McpTool.McpAnnotations(
            title = "Get latest version of a Maven artifact",
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    )
    fun getLatestVersion(
        @McpToolParam(description = "Maven groupId, e.g. org.springframework", required = true)
        groupId: String,
        @McpToolParam(description = "Maven artifactId, e.g. spring-core", required = true)
        artifactId: String,
        @McpToolParam(description = "Include pre-release versions (default false)", required = false)
        prerelease: Boolean? = null,
    ): Mono<LatestVersionResult> = mono {
        try {
            CoordinateValidator.validate(groupId, artifactId)?.let {
                return@mono LatestVersionResult(groupId, artifactId, null, STATUS_VALIDATION_ERROR, it.message)
            }
            when (val result = repository.fetchVersions(Coordinates(groupId, artifactId))) {
                is ArtifactResult.Success -> {
                    val resolved = if (prerelease == true) {
                        versionResolver.latest(result.value.versions)
                    } else {
                        versionResolver.latestStable(result.value.versions)
                    }
                    if (resolved != null) {
                        LatestVersionResult(groupId, artifactId, resolved, STATUS_OK)
                    } else {
                        LatestVersionResult(
                            groupId, artifactId, null, STATUS_NOT_FOUND, noStableMessage(groupId, artifactId),
                        )
                    }
                }

                is ArtifactResult.NotFound ->
                    LatestVersionResult(groupId, artifactId, null, STATUS_NOT_FOUND, result.message)

                is ArtifactResult.SourceError ->
                    LatestVersionResult(groupId, artifactId, null, STATUS_SOURCE_ERROR, result.message)

                is ArtifactResult.ValidationError ->
                    LatestVersionResult(groupId, artifactId, null, STATUS_VALIDATION_ERROR, result.message)
            }
        } catch (e: Exception) {
            LatestVersionResult(groupId, artifactId, null, STATUS_SOURCE_ERROR, unexpected(e))
        }
    }

    @McpTool(
        name = "list_versions",
        description = "List ALL published versions of a Maven artifact, newest first, with a total " +
                "count and the resolved latest stable version. For a Gradle plugin use its marker " +
                "coordinates: groupId = plugin id, artifactId = plugin id + '.gradle.plugin'.",
        annotations = McpTool.McpAnnotations(
            title = "List versions of a Maven artifact",
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    )
    fun listVersions(
        @McpToolParam(description = "Maven groupId", required = true) groupId: String,
        @McpToolParam(description = "Maven artifactId", required = true) artifactId: String,
    ): Mono<ListVersionsResult> = mono {
        try {
            CoordinateValidator.validate(groupId, artifactId)?.let {
                return@mono ListVersionsResult(
                    groupId, artifactId, status = STATUS_VALIDATION_ERROR, message = it.message,
                )
            }
            when (val result = repository.fetchVersions(Coordinates(groupId, artifactId))) {
                is ArtifactResult.Success -> {
                    val versions = result.value.versions
                    val descending = versionResolver.sortedDescending(versions)
                    ListVersionsResult(
                        groupId, artifactId, descending, descending.size,
                        versionResolver.latestStable(versions), STATUS_OK,
                    )
                }

                is ArtifactResult.NotFound ->
                    ListVersionsResult(groupId, artifactId, status = STATUS_NOT_FOUND, message = result.message)

                is ArtifactResult.SourceError ->
                    ListVersionsResult(groupId, artifactId, status = STATUS_SOURCE_ERROR, message = result.message)

                is ArtifactResult.ValidationError ->
                    ListVersionsResult(groupId, artifactId, status = STATUS_VALIDATION_ERROR, message = result.message)
            }
        } catch (e: Exception) {
            ListVersionsResult(groupId, artifactId, status = STATUS_SOURCE_ERROR, message = unexpected(e))
        }
    }

    @McpTool(
        name = "check_version",
        description = "Check whether a specific version of a Maven artifact is published. Returns " +
                "exists=true/false for an existing artifact; not_found only when the artifact itself is absent.",
        annotations = McpTool.McpAnnotations(
            title = "Check if a specific version of a Maven artifact exists",
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    )
    fun checkVersion(
        @McpToolParam(description = "Maven groupId", required = true) groupId: String,
        @McpToolParam(description = "Maven artifactId", required = true) artifactId: String,
        @McpToolParam(description = "Version to check, e.g. 1.2.3", required = true) version: String,
    ): Mono<CheckVersionResult> = mono {
        try {
            CoordinateValidator.validate(groupId, artifactId)?.let {
                return@mono CheckVersionResult(groupId, artifactId, version, null, STATUS_VALIDATION_ERROR, it.message)
            }
            when (val result = repository.fetchVersions(Coordinates(groupId, artifactId))) {
                is ArtifactResult.Success ->
                    CheckVersionResult(groupId, artifactId, version, version in result.value.versions, STATUS_OK)

                is ArtifactResult.NotFound ->
                    CheckVersionResult(groupId, artifactId, version, null, STATUS_NOT_FOUND, result.message)

                is ArtifactResult.SourceError ->
                    CheckVersionResult(groupId, artifactId, version, null, STATUS_SOURCE_ERROR, result.message)

                is ArtifactResult.ValidationError ->
                    CheckVersionResult(groupId, artifactId, version, null, STATUS_VALIDATION_ERROR, result.message)
            }
        } catch (e: Exception) {
            CheckVersionResult(groupId, artifactId, version, null, STATUS_SOURCE_ERROR, unexpected(e))
        }
    }

    @McpTool(
        name = "get_pom",
        description = "Return the raw POM XML of a Maven artifact for a specific version, or the " +
                "literal \"latest\" to resolve the latest stable version first (set prerelease=true to " +
                "resolve the latest including pre-releases). For a Gradle plugin marker artifact " +
                "(<plugin-id>.gradle.plugin) the POM names the plugin's implementation coordinates.",
        annotations = McpTool.McpAnnotations(
            title = "Get the raw POM XML of a Maven artifact",
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    )
    fun getPom(
        @McpToolParam(description = "Maven groupId", required = true) groupId: String,
        @McpToolParam(description = "Maven artifactId", required = true) artifactId: String,
        @McpToolParam(description = "Exact version, or the literal \"latest\"", required = true) version: String,
        @McpToolParam(description = "When version=latest, include pre-releases (default false)", required = false)
        prerelease: Boolean? = null,
    ): Mono<PomResult> = mono {
        try {
            CoordinateValidator.validate(groupId, artifactId)?.let {
                return@mono PomResult(groupId, artifactId, null, null, STATUS_VALIDATION_ERROR, it.message)
            }
            val coordinates = Coordinates(groupId, artifactId)
            // Nothing is fetched unless the version resolves: "latest" with only pre-releases
            // published must report not-found, never quietly hand back a pre-release POM.
            val resolvedVersion: String = when (val resolution = resolveVersion(coordinates, version, prerelease)) {
                is ArtifactResult.Success -> resolution.value
                is ArtifactResult.NotFound ->
                    return@mono PomResult(groupId, artifactId, null, null, STATUS_NOT_FOUND, resolution.message)

                is ArtifactResult.SourceError ->
                    return@mono PomResult(groupId, artifactId, null, null, STATUS_SOURCE_ERROR, resolution.message)

                is ArtifactResult.ValidationError ->
                    return@mono PomResult(groupId, artifactId, null, null, STATUS_VALIDATION_ERROR, resolution.message)
            }

            when (val pom = repository.fetchPom(coordinates, resolvedVersion)) {
                is ArtifactResult.Success ->
                    PomResult(groupId, artifactId, resolvedVersion, pom.value, STATUS_OK)

                is ArtifactResult.NotFound ->
                    PomResult(groupId, artifactId, resolvedVersion, null, STATUS_NOT_FOUND, pom.message)

                is ArtifactResult.SourceError ->
                    PomResult(groupId, artifactId, resolvedVersion, null, STATUS_SOURCE_ERROR, pom.message)

                is ArtifactResult.ValidationError ->
                    PomResult(groupId, artifactId, resolvedVersion, null, STATUS_VALIDATION_ERROR, pom.message)
            }
        } catch (e: Exception) {
            PomResult(groupId, artifactId, null, null, STATUS_SOURCE_ERROR, unexpected(e))
        }
    }

    @McpTool(
        name = "get_version_catalog",
        description = "Return the raw TOML of a Gradle version catalog that is published as a Maven " +
                "artifact (the coordinates used in settings.gradle.kts `from(\"group:artifact:version\")`). " +
                "Pass the literal \"latest\" as version to fetch the newest stable catalog. Use this to " +
                "see which dependency versions a shared catalog pins.",
        annotations = McpTool.McpAnnotations(
            title = "Get a raw TOML of a Gradle version catalog",
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    )
    fun getVersionCatalog(
        @McpToolParam(description = "Maven groupId of the catalog, e.g. com.example.commons", required = true)
        groupId: String,
        @McpToolParam(description = "Maven artifactId of the catalog, e.g. version-catalog", required = true)
        artifactId: String,
        @McpToolParam(description = "Exact version, or the literal \"latest\"", required = true)
        version: String,
        @McpToolParam(description = "When version=latest, include pre-releases (default false)", required = false)
        prerelease: Boolean? = null,
    ): Mono<VersionCatalogResult> = mono {
        try {
            CoordinateValidator.validate(groupId, artifactId)?.let {
                return@mono VersionCatalogResult(groupId, artifactId, null, null, STATUS_VALIDATION_ERROR, it.message)
            }
            val coordinates = Coordinates(groupId, artifactId)
            // Nothing is fetched unless the version resolves, exactly as in get_pom above.
            val resolvedVersion: String = when (val resolution = resolveVersion(coordinates, version, prerelease)) {
                is ArtifactResult.Success -> resolution.value
                is ArtifactResult.NotFound -> return@mono VersionCatalogResult(
                    groupId, artifactId, null, null, STATUS_NOT_FOUND, resolution.message,
                )

                is ArtifactResult.SourceError -> return@mono VersionCatalogResult(
                    groupId, artifactId, null, null, STATUS_SOURCE_ERROR, resolution.message,
                )

                is ArtifactResult.ValidationError -> return@mono VersionCatalogResult(
                    groupId, artifactId, null, null, STATUS_VALIDATION_ERROR, resolution.message,
                )
            }

            when (val toml = repository.fetchFile(coordinates, resolvedVersion, ArtifactFile.VERSION_CATALOG)) {
                is ArtifactResult.Success ->
                    VersionCatalogResult(groupId, artifactId, resolvedVersion, toml.value, STATUS_OK)

                is ArtifactResult.NotFound ->
                    VersionCatalogResult(groupId, artifactId, resolvedVersion, null, STATUS_NOT_FOUND, toml.message)

                is ArtifactResult.SourceError ->
                    VersionCatalogResult(groupId, artifactId, resolvedVersion, null, STATUS_SOURCE_ERROR, toml.message)

                is ArtifactResult.ValidationError ->
                    VersionCatalogResult(
                        groupId, artifactId, resolvedVersion, null, STATUS_VALIDATION_ERROR, toml.message,
                    )
            }
        } catch (e: Exception) {
            VersionCatalogResult(groupId, artifactId, null, null, STATUS_SOURCE_ERROR, unexpected(e))
        }
    }

    @McpTool(
        name = "search_artifacts",
        description = "Search for Maven artifacts matching a keyword query across Maven Central and " +
                "any configured internal registries (e.g. GitLab). Returns up to 'limit' matches (default " +
                "20, clamped to 1..100), each with its groupId, artifactId, and latest version. " +
                "Prefer 'a:<artifact-id>' when you know the artifact name — a plain term also " +
                "matches groupIds that merely contain it, which buries the obvious answer. If " +
                "partial=true a registry was unreachable and the list is incomplete, so absence of a " +
                "result does not mean the artifact does not exist — retry or check a coordinate directly.",
        annotations = McpTool.McpAnnotations(
            title = "Search for Maven artifacts",
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    )
    fun searchArtifacts(
        @McpToolParam(
            description = "Search term, passed to the registry's query syntax unchanged. A single " +
                "word or a hyphenated artifact name works well ('jackson-databind'). A BARE SPACE " +
                "is rejected with an error — quote a phrase (\"jackson databind\") or join terms " +
                "with AND. Prefix a: to match the artifactId only ('a:jackson-databind'), which " +
                "ranks the exact artifact above unrelated ones whose groupId merely contains the " +
                "term; g: does the same for groupId.",
            required = true,
        )
        query: String,
        @McpToolParam(description = "Max results, 1..100 (default 20)", required = false)
        limit: Int? = null,
    ): Mono<SearchArtifactsResult> = mono {
        try {
            val effectiveLimit = (limit ?: DEFAULT_SEARCH_LIMIT).coerceIn(MIN_SEARCH_LIMIT, MAX_SEARCH_LIMIT)
            when (val result = repository.search(query, effectiveLimit)) {
                is ArtifactResult.Success -> {
                    val outcome = result.value
                    SearchArtifactsResult(
                        query = query,
                        results = outcome.matches.map { SearchMatch(it.groupId, it.artifactId, it.latestVersion) },
                        status = STATUS_OK,
                        // Only set when something is actually missing, so a complete search stays quiet.
                        message = outcome.unavailableSources
                            .takeIf { it.isNotEmpty() }
                            ?.joinToString("; ", prefix = "Results are incomplete, some sources were unavailable: "),
                        partial = outcome.unavailableSources.isNotEmpty(),
                    )
                }

                is ArtifactResult.NotFound ->
                    SearchArtifactsResult(query, status = STATUS_NOT_FOUND, message = result.message)

                is ArtifactResult.SourceError ->
                    SearchArtifactsResult(query, status = STATUS_SOURCE_ERROR, message = result.message)

                is ArtifactResult.ValidationError ->
                    SearchArtifactsResult(query, status = STATUS_VALIDATION_ERROR, message = result.message)
            }
        } catch (e: Exception) {
            SearchArtifactsResult(query, status = STATUS_SOURCE_ERROR, message = unexpected(e))
        }
    }

    /**
     * Resolve the [version] a per-version file should be fetched at: the literal "latest" goes
     * through [VersionResolver] (stable, or any when [prerelease]), anything else is taken verbatim.
     * Failure variants are returned as data so callers can bail out BEFORE fetching anything.
     */
    private suspend fun resolveVersion(
        coordinates: Coordinates,
        version: String,
        prerelease: Boolean?,
    ): ArtifactResult<String> {
        if (!version.equals(LATEST, ignoreCase = true)) return ArtifactResult.Success(version)
        return when (val meta = repository.fetchVersions(coordinates)) {
            is ArtifactResult.Success -> {
                val resolved = if (prerelease == true) {
                    versionResolver.latest(meta.value.versions)
                } else {
                    versionResolver.latestStable(meta.value.versions)
                }
                resolved?.let { ArtifactResult.Success(it) }
                    ?: ArtifactResult.NotFound(noStableMessage(coordinates.groupId, coordinates.artifactId))
            }

            is ArtifactResult.NotFound -> meta
            is ArtifactResult.SourceError -> meta
            is ArtifactResult.ValidationError -> meta
        }
    }

    private fun noStableMessage(groupId: String, artifactId: String): String =
        "No stable version found for $groupId:$artifactId; pass prerelease=true to include pre-releases"

    private fun unexpected(e: Exception): String = "Unexpected error: ${e.message}"

    private companion object {
        const val STATUS_OK = "ok"
        const val STATUS_NOT_FOUND = "not_found"
        const val STATUS_SOURCE_ERROR = "source_error"
        const val STATUS_VALIDATION_ERROR = "validation_error"
        const val LATEST = "latest"
        const val DEFAULT_SEARCH_LIMIT = 20
        const val MIN_SEARCH_LIMIT = 1
        const val MAX_SEARCH_LIMIT = 100
    }
}
