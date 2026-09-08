package eu.zeletrik.ai.mavenmcp.resolver

import eu.zeletrik.ai.mavenmcp.artifact.ArtifactBackend
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactFile
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactMatch
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactMetadata
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactRepository
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactResult
import eu.zeletrik.ai.mavenmcp.artifact.Coordinates
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        // Unlike a coordinate lookup, search wants every backend's answer, and the backends are
        // independent — so fan out concurrently rather than paying them end to end. awaitAll keeps
        // the backends order, which is what makes the precedence rules below meaningful.
        val results = coroutineScope { backends.map { async { it.search(query, limit) } }.awaitAll() }

        val perBackend = mutableListOf<List<ArtifactMatch>>()
        var sourceError: ArtifactResult.SourceError? = null
        for (result in results) {
            when (result) {
                is ArtifactResult.Success -> perBackend += result.value
                is ArtifactResult.SourceError -> sourceError = result
                is ArtifactResult.NotFound -> Unit
                is ArtifactResult.ValidationError -> return result
            }
        }
        return when {
            // Non-empty means at least one backend answered, even if it matched nothing.
            perBackend.isNotEmpty() -> ArtifactResult.Success(interleave(perBackend, limit))
            sourceError != null -> sourceError
            else -> ArtifactResult.Success(emptyList())
        }
    }

    /**
     * Round-robin across the backends instead of concatenating them, so every backend that matched
     * is represented within [limit]. Concatenating let a chatty public registry fill the entire page
     * and hide internal artifacts completely — the opposite of what aggregating is for.
     *
     * De-duplication happens FIRST, in precedence order, so a coordinate offered by two backends
     * still resolves to the higher-precedence one; interleaving then only decides position, never
     * which copy survives.
     */
    private fun interleave(perBackend: List<List<ArtifactMatch>>, limit: Int): List<ArtifactMatch> {
        val claimed = mutableSetOf<Pair<String, String>>()
        val deduped = perBackend.map { matches -> matches.filter { claimed.add(it.groupId to it.artifactId) } }
        val rounds = deduped.maxOfOrNull { it.size } ?: 0
        return (0 until rounds)
            .flatMap { round -> deduped.mapNotNull { it.getOrNull(round) } }
            .take(limit)
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
