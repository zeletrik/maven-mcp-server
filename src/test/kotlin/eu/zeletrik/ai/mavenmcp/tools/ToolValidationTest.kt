package eu.zeletrik.ai.mavenmcp.tools

import eu.zeletrik.ai.mavenmcp.artifact.ArtifactRepository
import eu.zeletrik.ai.mavenmcp.artifact.VersionResolver
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Blank or invalid coordinates must be rejected with a validation_error BEFORE any repository call.
 *
 * The assertion is deliberately negative: a strict, unstubbed mock plus `coVerify(exactly = 0)`
 * proves no network path is reached. Checking only the returned status would pass even if the
 * implementation validated after firing the request.
 */
class ToolValidationTest {

    private val repository = mockk<ArtifactRepository>()
    private val tools = MavenArtifactTools(repository, VersionResolver())

    @Test
    fun `blank groupId is rejected without any repository call`() {
        val result = tools.getLatestVersion("", "demo", null).block()!!
        assertEquals("validation_error", result.status)
        coVerify(exactly = 0) { repository.fetchVersions(any()) }
    }

    @Test
    fun `blank artifactId is rejected for list_versions`() {
        assertEquals("validation_error", tools.listVersions("com.example", "  ").block()!!.status)
        coVerify(exactly = 0) { repository.fetchVersions(any()) }
    }

    @Test
    fun `path-traversal groupId is rejected for check_version`() {
        assertEquals("validation_error", tools.checkVersion("..", "demo", "1.0.0").block()!!.status)
        coVerify(exactly = 0) { repository.fetchVersions(any()) }
    }

    @Test
    fun `slash-bearing coordinate is rejected for get_pom without fetching`() {
        assertEquals("validation_error", tools.getPom("com/evil", "demo", "1.0.0", null).block()!!.status)
        coVerify(exactly = 0) { repository.fetchVersions(any()) }
        coVerify(exactly = 0) { repository.fetchPom(any(), any()) }
    }

    @Test
    fun `invalid character in artifactId is rejected`() {
        assertEquals("validation_error", tools.getLatestVersion("com.example", "de mo", null).block()!!.status)
        coVerify(exactly = 0) { repository.fetchVersions(any()) }
    }
}
