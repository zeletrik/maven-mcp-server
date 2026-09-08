package eu.zeletrik.ai.mavenmcp.resolver

import eu.zeletrik.ai.mavenmcp.artifact.ArtifactBackend
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactFile
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactMatch
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactMetadata
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactRepository
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactResult
import eu.zeletrik.ai.mavenmcp.artifact.Coordinates
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

/**
 * The primary [ArtifactRepository] the tools consume. For coordinate lookups (versions/POM) it
 * applies precedence by consulting the ordered [backends] (Maven Central first, then GitLab) and
 * returning the FIRST that has the artifact — failover. A backend's not-found lets the next be
 * tried; a source error is remembered so that, if no backend succeeds, an inconclusive source error
 * is surfaced in preference to a definitive not-found.
 *
 * [search] instead AGGREGATES across all backends so results include internal (GitLab) artifacts as
 * well as public ones — matches are concatenated in precedence order, de-duplicated by coordinates
 * (the higher-precedence backend wins a tie), and capped at the requested limit.
 *
 * [backends] is injected ordered by Spring `@Order`; the composite is not an [ArtifactBackend], so
 * it never injects itself.
 */
@Component
@Primary
class CompositeArtifactRepository(
    private val backends: List<ArtifactBackend>,
) : ArtifactRepository {

    override suspend fun fetchVersions(coordinates: Coordinates): ArtifactResult<ArtifactMetadata> =
        failover { it.fetchVersions(coordinates) }

    override suspend fun fetchFile(
        coordinates: Coordinates,
        version: String,
        file: ArtifactFile,
    ): ArtifactResult<String> =
        failover { it.fetchFile(coordinates, version, file) }

    override suspend fun search(query: String, limit: Int): ArtifactResult<List<ArtifactMatch>> {
        val merged = LinkedHashMap<Pair<String, String>, ArtifactMatch>()
        var anySuccess = false
        var sourceError: ArtifactResult.SourceError? = null
        for (backend in backends) {
            when (val result = backend.search(query, limit)) {
                is ArtifactResult.Success -> {
                    anySuccess = true
                    // Precedence order preserved; the first backend to supply a coordinate wins.
                    result.value.forEach { merged.putIfAbsent(it.groupId to it.artifactId, it) }
                }
                is ArtifactResult.SourceError -> sourceError = result
                is ArtifactResult.NotFound -> Unit
                is ArtifactResult.ValidationError -> return result
            }
            if (merged.size >= limit) break
        }
        return when {
            anySuccess -> ArtifactResult.Success(merged.values.take(limit))
            sourceError != null -> sourceError
            else -> ArtifactResult.Success(emptyList())
        }
    }

    private suspend fun <T> failover(
        operation: suspend (ArtifactBackend) -> ArtifactResult<T>,
    ): ArtifactResult<T> {
        var sourceError: ArtifactResult.SourceError? = null
        var notFound: ArtifactResult.NotFound? = null
        for (backend in backends) {
            when (val result = operation(backend)) {
                is ArtifactResult.Success -> return result
                is ArtifactResult.NotFound -> notFound = result
                is ArtifactResult.SourceError -> sourceError = result
                is ArtifactResult.ValidationError -> return result
            }
        }
        // No backend resolved it: a source error is inconclusive and outranks a definitive not-found.
        return sourceError ?: notFound ?: ArtifactResult.NotFound("No configured repository could resolve the request")
    }
}
