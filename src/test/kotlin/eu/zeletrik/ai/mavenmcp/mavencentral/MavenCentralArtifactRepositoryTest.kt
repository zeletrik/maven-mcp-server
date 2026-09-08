package eu.zeletrik.ai.mavenmcp.mavencentral

import eu.zeletrik.ai.mavenmcp.artifact.ArtifactFile
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactResult
import eu.zeletrik.ai.mavenmcp.artifact.Coordinates
import eu.zeletrik.ai.mavenmcp.mavenrepo.MavenHttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the Central adapter over real HTTP (MockWebServer) rather than a mocked port, so the
 * WebClient path, URL construction and status classification are all genuinely exercised.
 *
 * Covers the transport and parse matrix: a well-formed 200, a 404 mapping to not-found, a hanging
 * socket mapping to source-error within the bounded timeout, a refused connection, and a malformed
 * body. The recorded request paths are asserted too, since a silently wrong URL would otherwise
 * look identical to a missing artifact.
 */
class MavenCentralArtifactRepositoryTest {

    private val server = MockWebServer()

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun repo() = MavenCentralArtifactRepository(
        MavenHttpClient(WebClient.builder().build()),
        MavenCentralProperties(
            metadataBaseUrl = server.url("/").toString(),
            searchBaseUrl = server.url("/solrsearch/select").toString(),
            timeout = Duration.ofSeconds(5),
        ),
    )

    @Test
    fun `fetchVersions parses the version list from 200 metadata, ignoring latest and release`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/xml")
                .setBody(METADATA_XML),
        )

        val result = repo().fetchVersions(Coordinates("com.example", "demo"))

        assertTrue(result is ArtifactResult.Success, "expected success but was $result")
        assertEquals(listOf("1.0.0", "1.1.0", "2.0.0-RC1"), result.value.versions)
        val recorded = server.takeRequest()
        assertEquals("/com/example/demo/maven-metadata.xml", recorded.path)
    }

    private fun repoWithTimeout(millis: Long) = MavenCentralArtifactRepository(
        MavenHttpClient(WebClient.builder().build()),
        MavenCentralProperties(
            metadataBaseUrl = server.url("/").toString(),
            searchBaseUrl = server.url("/solrsearch/select").toString(),
            timeout = Duration.ofMillis(millis),
        ),
    )

    @Test
    fun `metadata 404 maps to not-found`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(repo().fetchVersions(Coordinates("com.example", "demo")) is ArtifactResult.NotFound)
    }

    @Test
    fun `metadata timeout via NO_RESPONSE maps to source-error within a bounded deadline`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        // A short client timeout must turn a hanging socket into a source-error (not a hang).
        val result = repoWithTimeout(500).fetchVersions(Coordinates("com.example", "demo"))
        assertTrue(result is ArtifactResult.SourceError, "expected source-error but was $result")
    }

    @Test
    fun `malformed metadata body maps to source-error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<<< this is not valid xml"))
        assertTrue(repo().fetchVersions(Coordinates("com.example", "demo")) is ArtifactResult.SourceError)
    }

    @Test
    fun `connection failure maps to source-error`() = runBlocking {
        val deadPortRepo = MavenCentralArtifactRepository(
            MavenHttpClient(WebClient.builder().build()),
            MavenCentralProperties(
                metadataBaseUrl = "http://localhost:1/",
                searchBaseUrl = "http://localhost:1/select",
                timeout = Duration.ofMillis(500),
            ),
        )
        assertTrue(deadPortRepo.fetchVersions(Coordinates("com.example", "demo")) is ArtifactResult.SourceError)
    }

    @Test
    fun `fetchPom returns raw XML on 200 and not-found on 404`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<project>raw</project>"))
        val ok = repo().fetchPom(Coordinates("com.example", "demo"), "1.2.3")
        assertTrue(ok is ArtifactResult.Success && ok.value == "<project>raw</project>")
        assertEquals("/com/example/demo/1.2.3/demo-1.2.3.pom", server.takeRequest().path)

        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(repo().fetchPom(Coordinates("com.example", "demo"), "9.9.9") is ArtifactResult.NotFound)
    }

    @Test
    fun `fetchFile addresses the toml sibling for a version catalog`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[versions]\nkotlin = \"2.4.0\""))

        val result =
            repo().fetchFile(Coordinates("com.example", "version-catalog"), "1.6.0", ArtifactFile.VERSION_CATALOG)

        assertTrue(result is ArtifactResult.Success, "expected success but was $result")
        assertEquals("[versions]\nkotlin = \"2.4.0\"", result.value)
        assertEquals(
            "/com/example/version-catalog/1.6.0/version-catalog-1.6.0.toml",
            server.takeRequest().path,
        )
    }

    @Test
    fun `search passes the query verbatim and tolerates a doc missing latestVersion`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(SOLR_JSON),
        )

        val result = repo().search("g:com.example", 20)

        assertTrue(result is ArtifactResult.Success, "expected success but was $result")
        assertEquals(2, result.value.size)
        assertEquals("1.2.3", result.value[0].latestVersion)
        assertNull(result.value[1].latestVersion, "missing latestVersion must parse to null, not error")
        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("q=g:com.example") || recorded.path!!.contains("q=g%3Acom.example"))
        assertTrue(recorded.path!!.contains("rows=20"))
    }

    private companion object {
        val SOLR_JSON = """
            {"responseHeader":{"status":0},"response":{"numFound":2,"docs":[
              {"id":"com.example:a","g":"com.example","a":"a","latestVersion":"1.2.3"},
              {"id":"com.example:b","g":"com.example","a":"b"}
            ]}}
        """.trimIndent()

        val METADATA_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata>
              <groupId>com.example</groupId>
              <artifactId>demo</artifactId>
              <versioning>
                <latest>2.0.0-RC1</latest>
                <release>1.1.0</release>
                <versions>
                  <version>1.0.0</version>
                  <version>1.1.0</version>
                  <version>2.0.0-RC1</version>
                </versions>
                <lastUpdated>20260101000000</lastUpdated>
              </versioning>
            </metadata>
        """.trimIndent()
    }
}
