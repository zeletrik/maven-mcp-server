package eu.zeletrik.ai.mavenmcp.gitlab

import eu.zeletrik.ai.mavenmcp.artifact.ArtifactResult
import eu.zeletrik.ai.mavenmcp.artifact.Coordinates
import eu.zeletrik.ai.mavenmcp.artifact.VersionResolver
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
 * Verifies the GitLab backend fetches metadata/POM from the configured Maven base URL, appends the
 * standard Maven path, and sends the configured auth header.
 */
class GitLabArtifactRepositoryTest {

    private val server = MockWebServer()

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun repo(header: GitLabProperties.TokenHeader = GitLabProperties.TokenHeader.PRIVATE_TOKEN) =
        GitLabArtifactRepository(
            MavenHttpClient(WebClient.builder().build()),
            GitLabProperties(
                enabled = true,
                baseUrl = server.url("/api/v4/projects/1/packages/maven").toString(),
                token = "s3cr3t",
                tokenHeader = header,
                timeout = Duration.ofSeconds(5),
            ),
            VersionResolver(),
        )

    @Test
    fun `fetchVersions hits the GitLab maven path and sends the Private-Token header`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(METADATA_XML))

        val result = repo().fetchVersions(Coordinates("com.example", "demo"))

        assertTrue(result is ArtifactResult.Success, "expected success but was $result")
        assertEquals(listOf("1.0.0", "1.1.0"), result.value.versions)
        val recorded = server.takeRequest()
        assertEquals("/api/v4/projects/1/packages/maven/com/example/demo/maven-metadata.xml", recorded.path)
        assertEquals("s3cr3t", recorded.getHeader("Private-Token"))
    }

    @Test
    fun `fetchPom returns raw XML and sends a Bearer header when configured`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<project>gl</project>"))

        val result = repo(GitLabProperties.TokenHeader.BEARER).fetchPom(Coordinates("com.example", "demo"), "1.1.0")

        assertTrue(result is ArtifactResult.Success && result.value == "<project>gl</project>")
        val recorded = server.takeRequest()
        assertEquals("/api/v4/projects/1/packages/maven/com/example/demo/1.1.0/demo-1.1.0.pom", recorded.path)
        assertEquals("Bearer s3cr3t", recorded.getHeader("Authorization"))
    }

    @Test
    fun `missing artifact maps to not-found`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(repo().fetchVersions(Coordinates("com.example", "demo")) is ArtifactResult.NotFound)
    }

    @Test
    fun `search queries the packages API and groups versions per artifact`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(PACKAGES_JSON),
        )

        val result = repo().search("starter", 20)

        assertTrue(result is ArtifactResult.Success, "expected success but was $result")
        assertEquals(2, result.value.size)
        val security = result.value.first { it.artifactId == "spring-boot-starter-security" }
        assertEquals("com.example.commons.springboot", security.groupId)
        assertEquals("2.0.1", security.latestVersion) // latest stable of [1.0.0, 2.0.1]
        val recorded = server.takeRequest()
        // packages API URL is derived from the maven base-url (…/packages/maven -> …/packages)
        assertTrue(recorded.path!!.startsWith("/api/v4/projects/1/packages?"), "path was ${recorded.path}")
        assertTrue(recorded.path!!.contains("package_type=maven") && recorded.path!!.contains("package_name=starter"))
        assertEquals("s3cr3t", recorded.getHeader("Private-Token"))
    }

    @Test
    fun `search is disabled (empty, no HTTP) when no packages URL can be derived`() = runBlocking {
        val repo = GitLabArtifactRepository(
            MavenHttpClient(WebClient.builder().build()),
            // base-url with no maven suffix and no explicit search-url -> not derivable
            GitLabProperties(enabled = true, baseUrl = "http://localhost:1/gitlab", token = "x"),
            VersionResolver(),
        )
        val result = repo.search("anything", 20)
        assertTrue(result is ArtifactResult.Success && result.value.isEmpty())
    }

    private companion object {
        val METADATA_XML = """
            <metadata><versioning><versions>
              <version>1.0.0</version><version>1.1.0</version>
            </versions></versioning></metadata>
        """.trimIndent()

        val PACKAGES_JSON = """
            [
              {"name":"com/example/commons/springboot/spring-boot-starter-security","version":"1.0.0"},
              {"name":"com/example/commons/springboot/spring-boot-starter-security","version":"2.0.1"},
              {"name":"com/example/tools/springboot/spring-boot-starter-metrics","version":"2.3.0"}
            ]
        """.trimIndent()
    }
}
