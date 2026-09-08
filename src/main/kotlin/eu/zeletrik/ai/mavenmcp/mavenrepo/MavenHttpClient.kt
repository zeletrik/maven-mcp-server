package eu.zeletrik.ai.mavenmcp.mavenrepo

import eu.zeletrik.ai.mavenmcp.artifact.ArtifactFile
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactMetadata
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactResult
import eu.zeletrik.ai.mavenmcp.artifact.Coordinates
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.dataformat.xml.XmlMapper
import tools.jackson.module.kotlin.kotlinModule
import java.net.URI
import java.time.Duration

/**
 * Shared transport for Maven-layout repositories: fetches maven-metadata.xml and raw per-version
 * files ([ArtifactFile]) from a given base URL, with optional auth headers, over the reactive
 * WebClient under a bounded timeout that cancels the in-flight request rather than waiting on the
 * socket.
 *
 * Outcomes are classified from the response STATUS and the exception type, never from response body
 * text, which would otherwise couple this code to a particular repository's error wording: HTTP 404
 * becomes not-found, while transport failures, timeouts and parse errors become source-error. Only
 * the metadata is parsed; artifact files are returned raw.
 *
 * Backends supply their own base URL and headers, so this class stays source-agnostic and every
 * repository shares one implementation of the fetch, encode and classify logic.
 */
// Catching Exception is deliberate: any parse or transport failure must become a SourceError result
// rather than propagate, since this client sits under a boundary that never throws.
@Suppress("TooGenericExceptionCaught")
@Component
class MavenHttpClient(
    private val webClient: WebClient,
) {

    private val xmlMapper: XmlMapper = XmlMapper.builder().addModule(kotlinModule()).build()

    suspend fun fetchVersions(
        baseUrl: String,
        headers: Map<String, String>,
        coordinates: Coordinates,
        timeout: Duration,
        redirectMeansNotFound: Boolean = false,
    ): ArtifactResult<ArtifactMetadata> {
        val label = coordinates.label()
        val uri = pathUri(baseUrl, coordinates.groupSegments() + coordinates.artifactId + "maven-metadata.xml")
        return when (val text = fetchText(uri, headers, "No artifact $label", label, timeout, redirectMeansNotFound)) {
            is ArtifactResult.Success -> parseMetadata(text.value, coordinates)
            is ArtifactResult.NotFound -> text
            is ArtifactResult.SourceError -> text
            is ArtifactResult.ValidationError -> text
        }
    }

    /**
     * Fetch the raw `…/{a}-{version}.{extension}` resource for [coordinates]. [file] is a closed
     * enum, so the extension never carries caller input into the URL.
     */
    suspend fun fetchArtifactFile(
        baseUrl: String,
        headers: Map<String, String>,
        coordinates: Coordinates,
        version: String,
        file: ArtifactFile,
        timeout: Duration,
        redirectMeansNotFound: Boolean = false,
    ): ArtifactResult<String> {
        val label = "${coordinates.label()}:$version"
        val fileName = "${coordinates.artifactId}-$version.${file.extension}"
        val uri = pathUri(baseUrl, coordinates.groupSegments() + coordinates.artifactId + version + fileName)
        return fetchText(uri, headers, "No $fileName for $label", label, timeout, redirectMeansNotFound)
    }

    /**
     * GET [uri] with [headers] and a bounded [timeout], mapping status/exception to the sealed result.
     *
     * The response entity (not just the body) is read so a redirect can be classified by STATUS
     * rather than by the empty body it arrives with. With [redirectMeansNotFound] a 3xx is
     * reported as not-found: WebClient does not follow redirects, and for a repository that fronts
     * another one, "see other" means "I do not host this" — which for a failover backend is a
     * not-found, not an error. Left false a 3xx keeps falling through to the empty-body source error.
     */
    suspend fun fetchText(
        uri: URI,
        headers: Map<String, String>,
        notFoundMessage: String,
        label: String,
        timeout: Duration,
        redirectMeansNotFound: Boolean = false,
    ): ArtifactResult<String> {
        val response = try {
            webClient.get()
                .uri(uri)
                .headers { target -> headers.forEach(target::add) }
                .retrieve()
                .toEntity(String::class.java)
                .timeout(timeout)
                .awaitSingleOrNull()
        } catch (e: WebClientResponseException) {
            return if (e.statusCode.value() == HTTP_NOT_FOUND) {
                ArtifactResult.NotFound(notFoundMessage)
            } else {
                ArtifactResult.SourceError("Repository returned HTTP ${e.statusCode.value()} for $label")
            }
        } catch (e: Exception) {
            return ArtifactResult.SourceError("Failed to reach repository for $label: ${e.message}")
        }
        if (redirectMeansNotFound && response?.statusCode?.is3xxRedirection == true) {
            return ArtifactResult.NotFound(notFoundMessage)
        }
        return when (val body = response?.body) {
            null -> ArtifactResult.SourceError("Empty response from repository for $label")
            else -> ArtifactResult.Success(body)
        }
    }

    private fun parseMetadata(xml: String, coordinates: Coordinates): ArtifactResult<ArtifactMetadata> =
        try {
            val parsed = xmlMapper.readValue(xml, MavenMetadataXml::class.java)
            ArtifactResult.Success(ArtifactMetadata(coordinates, parsed.versioning?.versions?.version ?: emptyList()))
        } catch (e: Exception) {
            ArtifactResult.SourceError("Unparseable metadata for ${coordinates.label()}: ${e.message}")
        }

    /** Append [segments] to [baseUrl], URL-encoding each one so no segment can escape the base. */
    private fun pathUri(baseUrl: String, segments: Array<String>): URI =
        UriComponentsBuilder.fromUriString(baseUrl).pathSegment(*segments).build().encode().toUri()

    private fun Coordinates.groupSegments(): Array<String> = groupId.split('.').toTypedArray()

    private fun Coordinates.label(): String = "$groupId:$artifactId"

    private companion object {
        const val HTTP_NOT_FOUND = 404
    }
}
