package eu.zeletrik.ai.mavenmcp.tools

import eu.zeletrik.ai.mavenmcp.artifact.ArtifactMetadata
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactRepository
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactResult
import eu.zeletrik.ai.mavenmcp.artifact.Coordinates
import eu.zeletrik.ai.mavenmcp.artifact.VersionResolver
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier

/**
 * Asserts the shape of the reactive boundary itself: each tool's Mono emits ONE structured next
 * signal and completes, for every domain variant, and never terminates with an error signal — an
 * errored Mono would surface to the client as a failed tool call instead of an answer it can act on.
 * Error results additionally carry no partial payload.
 *
 * StepVerifier is used rather than `block()` precisely because it can distinguish "completed with a
 * value" from "completed with an error", which is the property under test.
 */
class ToolMappingStepVerifierTest {

    private val repository = mockk<ArtifactRepository>()
    private val tools = MavenArtifactTools(repository, VersionResolver())
    private val coords = Coordinates("com.example", "demo")

    @Test
    fun `get_latest_version emits ok next signal on success`() {
        coEvery { repository.fetchVersions(coords) } returns
            ArtifactResult.Success(ArtifactMetadata(coords, listOf("1.0.0")))
        StepVerifier.create(tools.getLatestVersion("com.example", "demo", null))
            .expectNextMatches { it.status == "ok" && it.latestVersion == "1.0.0" }
            .verifyComplete()
    }

    @Test
    fun `get_latest_version emits a next signal (not an error) on source error`() {
        coEvery { repository.fetchVersions(coords) } returns ArtifactResult.SourceError("boom")
        StepVerifier.create(tools.getLatestVersion("com.example", "demo", null))
            .expectNextMatches { it.status == "source_error" }
            .verifyComplete()
    }

    @Test
    fun `check_version emits a next signal (not an error) on not-found`() {
        coEvery { repository.fetchVersions(coords) } returns ArtifactResult.NotFound("gone")
        StepVerifier.create(tools.checkVersion("com.example", "demo", "1.0.0"))
            .expectNextMatches { it.status == "not_found" && it.exists == null }
            .verifyComplete()
    }

    @Test
    fun `list_versions carries no partial payload on source error`() {
        coEvery { repository.fetchVersions(coords) } returns ArtifactResult.SourceError("boom")
        StepVerifier.create(tools.listVersions("com.example", "demo"))
            .expectNextMatches {
                it.status == "source_error" && it.versions.isEmpty() && it.total == 0 && it.latestStable == null
            }
            .verifyComplete()
    }

    @Test
    fun `get_pom carries no partial payload on source error`() {
        coEvery { repository.fetchPom(coords, "1.2.3") } returns ArtifactResult.SourceError("boom")
        StepVerifier.create(tools.getPom("com.example", "demo", "1.2.3", null))
            .expectNextMatches { it.status == "source_error" && it.pomXml == null }
            .verifyComplete()
    }
}
