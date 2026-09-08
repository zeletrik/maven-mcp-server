package eu.zeletrik.ai.mavenmcp.resolver

import eu.zeletrik.ai.mavenmcp.artifact.ArtifactBackend
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactMatch
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactMetadata
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactResult
import eu.zeletrik.ai.mavenmcp.artifact.Coordinates
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Failover precedence: the composite consults backends in order (central first, then gitlab) and
 * returns the first that has the artifact. A not-found tries the next; a source error is remembered
 * and outranks a definitive not-found when nothing succeeds.
 */
class CompositeArtifactRepositoryTest {

    private val central = mockk<ArtifactBackend>()
    private val gitlab = mockk<ArtifactBackend>()
    private val composite = CompositeArtifactRepository(listOf(central, gitlab))
    private val coords = Coordinates("com.example", "demo")

    private fun versions(vararg v: String) = ArtifactResult.Success(ArtifactMetadata(coords, v.toList()))

    @Test
    fun `central hit wins and gitlab is not consulted`() = runBlocking {
        coEvery { central.fetchVersions(coords) } returns versions("1.0.0")
        val result = composite.fetchVersions(coords)
        assertEquals(versions("1.0.0"), result)
        coVerify(exactly = 0) { gitlab.fetchVersions(any()) }
    }

    @Test
    fun `central not-found falls over to gitlab`() = runBlocking {
        coEvery { central.fetchVersions(coords) } returns ArtifactResult.NotFound("not in central")
        coEvery { gitlab.fetchVersions(coords) } returns versions("2.0.0")
        assertEquals(versions("2.0.0"), composite.fetchVersions(coords))
    }

    @Test
    fun `not-found in all backends yields not-found`() = runBlocking {
        coEvery { central.fetchVersions(coords) } returns ArtifactResult.NotFound("no")
        coEvery { gitlab.fetchVersions(coords) } returns ArtifactResult.NotFound("no")
        assertTrue(composite.fetchVersions(coords) is ArtifactResult.NotFound)
    }

    @Test
    fun `a central source error still allows gitlab to satisfy the request`() = runBlocking {
        coEvery { central.fetchVersions(coords) } returns ArtifactResult.SourceError("central down")
        coEvery { gitlab.fetchVersions(coords) } returns versions("3.0.0")
        assertEquals(versions("3.0.0"), composite.fetchVersions(coords))
    }

    @Test
    fun `a source error outranks a definitive not-found when nothing succeeds`() = runBlocking {
        coEvery { central.fetchVersions(coords) } returns ArtifactResult.SourceError("central down")
        coEvery { gitlab.fetchVersions(coords) } returns ArtifactResult.NotFound("not in gitlab")
        assertTrue(composite.fetchVersions(coords) is ArtifactResult.SourceError)
    }

    @Test
    fun `search aggregates central and gitlab, central winning duplicate coordinates`() = runBlocking {
        coEvery { central.search("q", 20) } returns ArtifactResult.Success(
            listOf(ArtifactMatch("g", "public", "3.0"), ArtifactMatch("g", "shared", "central-v")),
        )
        coEvery { gitlab.search("q", 20) } returns ArtifactResult.Success(
            listOf(ArtifactMatch("g", "shared", "gitlab-v"), ArtifactMatch("g", "internal", "2.0")),
        )

        val result = composite.search("q", 20)

        assertTrue(result is ArtifactResult.Success)
        // Both backends consulted; results merged in precedence order; 'shared' deduped to Central's.
        assertEquals(
            listOf("public" to "3.0", "shared" to "central-v", "internal" to "2.0"),
            result.value.map { it.artifactId to it.latestVersion },
        )
        coVerify { gitlab.search("q", 20) }
    }

    @Test
    fun `search still returns gitlab results when central errors`() = runBlocking {
        coEvery { central.search("q", 20) } returns ArtifactResult.SourceError("central down")
        coEvery { gitlab.search("q", 20) } returns ArtifactResult.Success(listOf(ArtifactMatch("g", "internal", "2.0")))

        val result = composite.search("q", 20)

        assertTrue(result is ArtifactResult.Success && result.value.single().artifactId == "internal")
    }
}
