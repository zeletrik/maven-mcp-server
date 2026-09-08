package eu.zeletrik.ai.mavenmcp.mavencentral

import eu.zeletrik.ai.mavenmcp.artifact.ArtifactBackend
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactFile
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactMatch
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactMetadata
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactResult
import eu.zeletrik.ai.mavenmcp.artifact.Coordinates
import eu.zeletrik.ai.mavenmcp.artifact.SearchOutcome
import eu.zeletrik.ai.mavenmcp.mavenrepo.MavenHttpClient
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Repository
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/** Maven Central has the highest precedence in the failover order: it is consulted first. */
private const val CENTRAL_PRECEDENCE = 0

/**
 * Maven Central backend (highest precedence, [Order] 0). Version and file access are delegated to
 * the shared [MavenHttpClient]; keyword search is Central-specific, using its Solr endpoint, which
 * is why this backend carries its own JSON mapper.
 */
// See MavenHttpClient: a broad catch is how parse failures become SourceError instead of throwing.
@Suppress("TooGenericExceptionCaught")
@Repository
@Order(CENTRAL_PRECEDENCE)
class MavenCentralArtifactRepository(
    private val client: MavenHttpClient,
    private val properties: MavenCentralProperties,
) : ArtifactBackend {

    private val jsonMapper: JsonMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    override suspend fun fetchVersions(coordinates: Coordinates): ArtifactResult<ArtifactMetadata> =
        client.fetchVersions(properties.metadataBaseUrl, NO_HEADERS, coordinates, properties.timeout)

    override suspend fun fetchFile(
        coordinates: Coordinates,
        version: String,
        file: ArtifactFile,
    ): ArtifactResult<String> =
        client.fetchArtifactFile(properties.metadataBaseUrl, NO_HEADERS, coordinates, version, file, properties.timeout)

    override suspend fun search(query: String, limit: Int): ArtifactResult<SearchOutcome> {
        val uri = UriComponentsBuilder.fromUriString(properties.searchBaseUrl)
            .queryParam("q", query)
            .queryParam("rows", limit)
            .queryParam("wt", "json")
            .build()
            .encode()
            .toUri()
        val text = client.fetchText(
            uri,
            NO_HEADERS,
            notFoundMessage = "Search endpoint not found",
            label = "search '$query'",
            timeout = properties.timeout,
        )
        return when (text) {
            is ArtifactResult.Success -> parseSearch(text.value, query)
            is ArtifactResult.NotFound -> text
            is ArtifactResult.SourceError -> text
            is ArtifactResult.ValidationError -> text
        }
    }

    private fun parseSearch(json: String, query: String): ArtifactResult<SearchOutcome> =
        try {
            val parsed = jsonMapper.readValue(json, SolrSearchResponse::class.java)
            val matches = parsed.response.docs.mapNotNull { doc ->
                if (doc.g != null && doc.a != null) ArtifactMatch(doc.g, doc.a, doc.latestVersion) else null
            }
            ArtifactResult.Success(SearchOutcome(matches))
        } catch (e: Exception) {
            ArtifactResult.SourceError("Unparseable search response for '$query': ${e.message}")
        }

    private companion object {
        val NO_HEADERS = emptyMap<String, String>()
    }
}
