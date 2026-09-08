package eu.zeletrik.ai.mavenmcp.tools

import eu.zeletrik.ai.mavenmcp.artifact.ArtifactMetadata
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactRepository
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactResult
import eu.zeletrik.ai.mavenmcp.artifact.Coordinates
import eu.zeletrik.ai.mavenmcp.artifact.VersionResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tool-layer tests for the four version-lookup tools with a mocked ArtifactRepository (no HTTP).
 * Each proves the mono{} boundary maps every domain variant onto a structured result.
 */
class MavenArtifactToolsTest {

    private val repository = mockk<ArtifactRepository>()
    private val tools = MavenArtifactTools(repository, VersionResolver())
    private val coords = Coordinates("com.example", "demo")

    private fun versions(vararg v: String) = ArtifactResult.Success(ArtifactMetadata(coords, v.toList()))

    // ---- get_latest_version ----

    @Test
    fun `get_latest_version returns highest stable by default, excluding pre-releases`() {
        coEvery { repository.fetchVersions(coords) } returns versions("1.0.0", "1.1.0", "2.0.0-RC1")
        val result = tools.getLatestVersion("com.example", "demo", null).block()!!
        assertEquals("ok", result.status)
        assertEquals("1.1.0", result.latestVersion)
    }

    @Test
    fun `get_latest_version with prerelease=true returns the highest including pre-releases`() {
        coEvery { repository.fetchVersions(coords) } returns versions("1.0.0", "1.1.0", "2.0.0-RC1")
        val result = tools.getLatestVersion("com.example", "demo", true).block()!!
        assertEquals("ok", result.status)
        assertEquals("2.0.0-RC1", result.latestVersion)
    }

    @Test
    fun `get_latest_version returns not_found (not a pre-release) when no stable version exists`() {
        coEvery { repository.fetchVersions(coords) } returns versions("1.0.0-alpha1", "2.0.0-SNAPSHOT")
        val result = tools.getLatestVersion("com.example", "demo", null).block()!!
        assertEquals("not_found", result.status)
        assertNull(result.latestVersion)
    }

    @Test
    fun `get_latest_version maps source error`() {
        coEvery { repository.fetchVersions(coords) } returns ArtifactResult.SourceError("boom")
        assertEquals("source_error", tools.getLatestVersion("com.example", "demo", null).block()!!.status)
    }

    // ---- list_versions ----

    @Test
    fun `list_versions returns all versions descending with total and latest stable`() {
        coEvery { repository.fetchVersions(coords) } returns versions("1.0.0", "2.0.0-RC1", "1.10.0", "1.2.0")
        val result = tools.listVersions("com.example", "demo").block()!!
        assertEquals("ok", result.status)
        assertEquals(listOf("2.0.0-RC1", "1.10.0", "1.2.0", "1.0.0"), result.versions)
        assertEquals(4, result.total)
        assertEquals("1.10.0", result.latestStable)
    }

    @Test
    fun `list_versions maps artifact not found`() {
        coEvery { repository.fetchVersions(coords) } returns ArtifactResult.NotFound("missing")
        assertEquals("not_found", tools.listVersions("com.example", "demo").block()!!.status)
    }

    // ---- check_version ----

    @Test
    fun `check_version reports exists true for a published version`() {
        coEvery { repository.fetchVersions(coords) } returns versions("1.0.0", "1.1.0")
        val result = tools.checkVersion("com.example", "demo", "1.1.0").block()!!
        assertEquals("ok", result.status)
        assertEquals(true, result.exists)
    }

    @Test
    fun `check_version reports exists false for a missing version on an existing artifact`() {
        coEvery { repository.fetchVersions(coords) } returns versions("1.0.0", "1.1.0")
        val result = tools.checkVersion("com.example", "demo", "9.9.9").block()!!
        assertEquals("ok", result.status)
        assertEquals(false, result.exists)
    }

    @Test
    fun `check_version maps a missing artifact to not_found with null exists`() {
        coEvery { repository.fetchVersions(coords) } returns ArtifactResult.NotFound("gone")
        val result = tools.checkVersion("com.example", "demo", "1.0.0").block()!!
        assertEquals("not_found", result.status)
        assertNull(result.exists)
    }

    // ---- get_pom ----

    @Test
    fun `get_pom returns raw xml and resolved coordinates for a specific version`() {
        coEvery { repository.fetchPom(coords, "1.2.3") } returns ArtifactResult.Success("<project>demo</project>")
        val result = tools.getPom("com.example", "demo", "1.2.3", null).block()!!
        assertEquals("ok", result.status)
        assertEquals("1.2.3", result.version)
        assertEquals("<project>demo</project>", result.pomXml)
    }

    @Test
    fun `get_pom latest resolves to the highest stable version before fetching`() {
        coEvery { repository.fetchVersions(coords) } returns versions("1.0.0", "1.1.0", "2.0.0-RC1")
        coEvery { repository.fetchPom(coords, "1.1.0") } returns ArtifactResult.Success("<project/>")
        val result = tools.getPom("com.example", "demo", "latest", null).block()!!
        assertEquals("ok", result.status)
        assertEquals("1.1.0", result.version)
    }

    @Test
    fun `get_pom latest with prerelease=true resolves to the highest including pre-releases`() {
        coEvery { repository.fetchVersions(coords) } returns versions("1.0.0", "1.1.0", "2.0.0-RC1")
        coEvery { repository.fetchPom(coords, "2.0.0-RC1") } returns ArtifactResult.Success("<project/>")
        val result = tools.getPom("com.example", "demo", "latest", true).block()!!
        assertEquals("2.0.0-RC1", result.version)
    }

    @Test
    fun `get_pom latest with no stable version returns not_found and never fetches a POM`() {
        coEvery { repository.fetchVersions(coords) } returns versions("1.0.0-RC1", "2.0.0-SNAPSHOT")
        val result = tools.getPom("com.example", "demo", "latest", null).block()!!
        assertEquals("not_found", result.status)
        coVerify(exactly = 0) { repository.fetchPom(any(), any()) }
    }

    @Test
    fun `get_pom for a missing specific version returns not_found`() {
        coEvery { repository.fetchPom(coords, "9.9.9") } returns ArtifactResult.NotFound("no pom")
        assertEquals("not_found", tools.getPom("com.example", "demo", "9.9.9", null).block()!!.status)
    }
}
