package eu.zeletrik.ai.mavenmcp.tools

import eu.zeletrik.ai.mavenmcp.artifact.ArtifactMatch
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactRepository
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactResult
import eu.zeletrik.ai.mavenmcp.artifact.SearchOutcome
import eu.zeletrik.ai.mavenmcp.artifact.VersionResolver
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * search_artifacts returns items carrying exactly the three coordinate fields, and a zero-match
 * query yields an empty-list SUCCESS rather than an error — "nothing matched" is a valid answer, and
 * reporting it as a failure would push a calling model toward retrying a query that worked fine.
 */
class SearchArtifactsToolTest {

    private val repository = mockk<ArtifactRepository>()
    private val tools = MavenArtifactTools(repository, VersionResolver())

    @Test
    fun `search_artifacts returns matches with groupId, artifactId and latestVersion`() {
        coEvery { repository.search("jackson", any()) } returns ArtifactResult.Success(
            SearchOutcome(
                listOf(
                    ArtifactMatch("com.fasterxml.jackson.core", "jackson-databind", "3.1.4"),
                    ArtifactMatch("com.example", "no-latest", null),
                ),
            ),
        )

        val result = tools.searchArtifacts("jackson", null).block()!!

        assertEquals("ok", result.status)
        assertEquals(2, result.results.size)
        assertEquals("com.fasterxml.jackson.core", result.results[0].groupId)
        assertEquals("jackson-databind", result.results[0].artifactId)
        assertEquals("3.1.4", result.results[0].latestVersion)
        assertEquals(null, result.results[1].latestVersion)
    }

    @Test
    fun `search_artifacts returns an empty list (not an error) on no matches`() {
        coEvery { repository.search(any(), any()) } returns ArtifactResult.Success(SearchOutcome(emptyList()))

        val result = tools.searchArtifacts("nomatchxyz", null).block()!!

        assertEquals("ok", result.status)
        assertTrue(result.results.isEmpty())
    }

    @Test
    fun `an unavailable source surfaces as partial, with the results still returned`() {
        coEvery { repository.search(any(), any()) } returns ArtifactResult.Success(
            SearchOutcome(
                matches = listOf(ArtifactMatch("g", "internal", "2.0")),
                unavailableSources = listOf("central unreachable"),
            ),
        )

        val result = tools.searchArtifacts("q", null).block()!!

        // Still ok: the call worked and the matches are real. partial says the list is short
        // because a registry is down, not because the query matched little.
        assertEquals("ok", result.status)
        assertEquals(1, result.results.size)
        assertTrue(result.partial, "a search missing a source must announce it")
        assertTrue(result.message!!.contains("central unreachable"), "message names what was unavailable")
    }

    @Test
    fun `a complete search is not marked partial`() {
        coEvery { repository.search(any(), any()) } returns ArtifactResult.Success(
            SearchOutcome(listOf(ArtifactMatch("g", "a", "1.0"))),
        )

        val result = tools.searchArtifacts("q", null).block()!!

        assertFalse(result.partial)
        assertEquals(null, result.message)
    }

    @Test
    fun `search_artifacts maps a source error`() {
        coEvery { repository.search(any(), any()) } returns ArtifactResult.SourceError("boom")
        assertEquals("source_error", tools.searchArtifacts("q", null).block()!!.status)
    }
}
