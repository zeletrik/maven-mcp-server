package eu.zeletrik.ai.mavenmcp.tools

import eu.zeletrik.ai.mavenmcp.artifact.ArtifactFile
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
 * Tool-layer tests for `get_version_catalog` with a mocked ArtifactRepository (no HTTP). Mirrors the
 * get_pom coverage: raw body passthrough, "latest" resolution, and each domain variant mapped to a
 * structured result rather than thrown. The negative paths additionally assert via
 * `coVerify(exactly = 0)` that nothing is fetched when the version cannot resolve — resolving to
 * nothing and then fetching anyway is independently possible, so it is checked independently.
 */
class VersionCatalogToolTest {

    private val repository = mockk<ArtifactRepository>()
    private val tools = MavenArtifactTools(repository, VersionResolver())
    private val coords = Coordinates("com.example.commons", "version-catalog")
    private val toml = "[versions]\nkotlin = \"2.4.0\"\n"

    private fun versions(vararg v: String) = ArtifactResult.Success(ArtifactMetadata(coords, v.toList()))

    private fun catalog(version: String, prerelease: Boolean? = null) =
        tools.getVersionCatalog(coords.groupId, coords.artifactId, version, prerelease).block()!!

    @Test
    fun `returns the raw TOML unparsed for an explicit version`() {
        coEvery {
            repository.fetchFile(coords, "1.6.0", ArtifactFile.VERSION_CATALOG)
        } returns ArtifactResult.Success(toml)

        val result = catalog("1.6.0")

        assertEquals("ok", result.status)
        assertEquals("1.6.0", result.version)
        assertEquals(toml, result.toml)
    }

    @Test
    fun `version=latest resolves to the highest stable before fetching`() {
        coEvery { repository.fetchVersions(coords) } returns versions("1.6.0", "2.1.2", "2.2.0-RC1")
        coEvery {
            repository.fetchFile(coords, "2.1.2", ArtifactFile.VERSION_CATALOG)
        } returns ArtifactResult.Success(toml)

        val result = catalog("latest")

        assertEquals("ok", result.status)
        assertEquals("2.1.2", result.version)
        coVerify(exactly = 0) { repository.fetchFile(coords, "2.2.0-RC1", ArtifactFile.VERSION_CATALOG) }
    }

    @Test
    fun `version=latest with prerelease=true resolves to the highest including pre-releases`() {
        coEvery { repository.fetchVersions(coords) } returns versions("2.1.2", "2.2.0-RC1")
        coEvery {
            repository.fetchFile(coords, "2.2.0-RC1", ArtifactFile.VERSION_CATALOG)
        } returns ArtifactResult.Success(toml)

        assertEquals("2.2.0-RC1", catalog("latest", prerelease = true).version)
    }

    @Test
    fun `version=latest with no stable version returns not_found and fetches nothing`() {
        coEvery { repository.fetchVersions(coords) } returns versions("1.0.0-alpha1", "2.0.0-SNAPSHOT")

        val result = catalog("latest")

        assertEquals("not_found", result.status)
        assertNull(result.toml)
        assertNull(result.version)
        coVerify(exactly = 0) { repository.fetchFile(any(), any(), any()) }
    }

    @Test
    fun `a blank groupId is rejected before any repository call`() {
        val result = tools.getVersionCatalog("  ", "version-catalog", "1.6.0", null).block()!!

        assertEquals("validation_error", result.status)
        assertNull(result.toml)
        coVerify(exactly = 0) { repository.fetchFile(any(), any(), any()) }
        coVerify(exactly = 0) { repository.fetchVersions(any()) }
    }

    @Test
    fun `an artifact that publishes no catalog maps to not_found`() {
        coEvery {
            repository.fetchFile(coords, "1.6.0", ArtifactFile.VERSION_CATALOG)
        } returns ArtifactResult.NotFound("no catalog")

        val result = catalog("1.6.0")

        assertEquals("not_found", result.status)
        assertEquals("no catalog", result.message)
        assertNull(result.toml)
    }

    @Test
    fun `a source error carries no partial payload`() {
        coEvery {
            repository.fetchFile(coords, "1.6.0", ArtifactFile.VERSION_CATALOG)
        } returns ArtifactResult.SourceError("registry unreachable")

        val result = catalog("1.6.0")

        assertEquals("source_error", result.status)
        assertNull(result.toml)
    }
}
