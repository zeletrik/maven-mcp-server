package eu.zeletrik.ai.mavenmcp.gradleplugins

import eu.zeletrik.ai.mavenmcp.artifact.ArtifactFile
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactResult
import eu.zeletrik.ai.mavenmcp.artifact.Coordinates
import eu.zeletrik.ai.mavenmcp.mavenrepo.MavenHttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the portal backend over real HTTP (MockWebServer). The load-bearing case is the 303: the
 * portal answers one for every coordinate it does not host itself, and it MUST surface as not-found
 * — as a source error it would outrank a definitive not-found in the composite's failover and
 * degrade every "exists nowhere" lookup across all backends.
 */
class GradlePluginPortalArtifactRepositoryTest {

    private val server = MockWebServer()

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun repo() = GradlePluginPortalArtifactRepository(
        MavenHttpClient(WebClient.builder().build()),
        GradlePluginPortalProperties(
            enabled = true,
            baseUrl = server.url("/m2/").toString(),
            timeout = Duration.ofSeconds(5),
        ),
    )

    private val marker = Coordinates("org.sonarqube", "org.sonarqube.gradle.plugin")

    @Test
    fun `fetchVersions reads plugin marker metadata from the portal's maven layout`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/xml")
                .setBody(MARKER_METADATA),
        )

        val result = repo().fetchVersions(marker)

        assertTrue(result is ArtifactResult.Success, "expected success but was $result")
        assertEquals(listOf("7.4.0.8496", "7.5.0.8588"), result.value.versions)
        assertEquals(
            "/m2/org/sonarqube/org.sonarqube.gradle.plugin/maven-metadata.xml",
            server.takeRequest().path,
        )
    }

    @Test
    fun `a 303 to the fronted repository maps to not-found, never a source error`() = runBlocking {
        // What the real portal answers for a coordinate it does not host: empty body + Location.
        server.enqueue(
            MockResponse()
                .setResponseCode(303)
                .setHeader("Location", "https://repo.maven.apache.org/maven2/com/example/demo/maven-metadata.xml"),
        )

        val result = repo().fetchVersions(Coordinates("com.example", "demo"))

        assertTrue(result is ArtifactResult.NotFound, "a 303 must be not-found but was $result")
    }

    @Test
    fun `a 303 on a per-version file also maps to not-found`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(303).setHeader("Location", "https://repo.maven.apache.org/maven2/x"),
        )

        val result = repo().fetchFile(Coordinates("com.example", "demo"), "1.0.0", ArtifactFile.POM)

        assertTrue(result is ArtifactResult.NotFound, "a 303 must be not-found but was $result")
    }

    @Test
    fun `fetchFile returns the raw marker POM naming the implementation artifact`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(MARKER_POM))

        val result = repo().fetchFile(marker, "7.5.0.8588", ArtifactFile.POM)

        assertTrue(result is ArtifactResult.Success, "expected success but was $result")
        assertTrue(result.value.contains("sonarqube-gradle-plugin"), "raw POM should be passed through unparsed")
        assertEquals(
            "/m2/org/sonarqube/org.sonarqube.gradle.plugin/7.5.0.8588/org.sonarqube.gradle.plugin-7.5.0.8588.pom",
            server.takeRequest().path,
        )
    }

    @Test
    fun `404 still maps to not-found`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(repo().fetchVersions(marker) is ArtifactResult.NotFound)
    }

    @Test
    fun `search contributes nothing and issues no request`() = runBlocking {
        val result = repo().search("sonarqube", 20)

        assertTrue(result is ArtifactResult.Success && result.value.matches.isEmpty())
        assertEquals(0, server.requestCount, "the portal has no search API, so it must not call out")
    }

    private companion object {
        val MARKER_METADATA = """
            <metadata><versioning><versions>
              <version>7.4.0.8496</version><version>7.5.0.8588</version>
            </versions></versioning></metadata>
        """.trimIndent()

        val MARKER_POM = """
            <project><modelVersion>4.0.0</modelVersion>
              <groupId>org.sonarqube</groupId>
              <artifactId>org.sonarqube.gradle.plugin</artifactId>
              <version>7.5.0.8588</version><packaging>pom</packaging>
              <dependencies><dependency>
                <groupId>org.sonarsource.scanner.gradle</groupId>
                <artifactId>sonarqube-gradle-plugin</artifactId>
                <version>7.5.0.8588</version>
              </dependency></dependencies>
            </project>
        """.trimIndent()
    }
}
