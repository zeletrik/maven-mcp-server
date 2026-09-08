package eu.zeletrik.ai.mavenmcp.tools

import eu.zeletrik.ai.mavenmcp.artifact.ArtifactRepository
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactResult
import eu.zeletrik.ai.mavenmcp.artifact.SearchOutcome
import eu.zeletrik.ai.mavenmcp.artifact.VersionResolver
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Boundary coverage for the search_artifacts limit. Each case asserts the EFFECTIVE limit forwarded
 * to the backend: below-range clamps up to 1, both bounds pass through unchanged, above-range clamps
 * down to 100, and an omitted limit defaults to 20.
 *
 * The boundaries are covered explicitly because the plausible bugs here are off-by-one (`> 100` vs
 * `>= 100`) and a null-coalesce swallowing an explicit `0` — neither of which a mid-range case
 * would reveal.
 */
class SearchArtifactsLimitBoundaryTest {

    private val repository = mockk<ArtifactRepository>()
    private val tools = MavenArtifactTools(repository, VersionResolver())

    private fun effectiveLimitFor(requested: Int?): Int {
        val captured = slot<Int>()
        coEvery { repository.search(any(), capture(captured)) } returns
            ArtifactResult.Success(SearchOutcome(emptyList()))
        tools.searchArtifacts("q", requested).block()
        return captured.captured
    }

    @Test
    fun `below-range limit clamps up to 1`() = assertEquals(1, effectiveLimitFor(0))

    @Test
    fun `lower boundary 1 passes through`() = assertEquals(1, effectiveLimitFor(1))

    @Test
    fun `within-range limit passes through`() = assertEquals(20, effectiveLimitFor(20))

    @Test
    fun `upper boundary 100 passes through`() = assertEquals(100, effectiveLimitFor(100))

    @Test
    fun `above-range limit clamps down to 100`() = assertEquals(100, effectiveLimitFor(101))

    @Test
    fun `omitted limit defaults to 20`() = assertEquals(20, effectiveLimitFor(null))
}
