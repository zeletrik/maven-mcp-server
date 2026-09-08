package eu.zeletrik.ai.mavenmcp.tools

import eu.zeletrik.ai.mavenmcp.artifact.ArtifactBackend
import eu.zeletrik.ai.mavenmcp.artifact.ArtifactRepository
import eu.zeletrik.ai.mavenmcp.gradleplugins.GradlePluginPortalArtifactRepository
import eu.zeletrik.ai.mavenmcp.mavencentral.MavenCentralArtifactRepository
import eu.zeletrik.ai.mavenmcp.resolver.CompositeArtifactRepository
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the tools actually reach an MCP client. Booting under `spring.ai.mcp.server.type=ASYNC`
 * must register EXACTLY the expected snake_case tool names: a `suspend` or non-reactive tool method
 * is silently ignored by the annotation scanner, so a broken tool surface looks like a perfectly
 * green unit-test suite. Only starting the context catches it.
 *
 * Also asserts that the tools receive the aggregating repository rather than a single backend.
 *
 * Tool names are collected from BOTH the session-based and the stateless specification types:
 * `spring.ai.mcp.server.protocol` decides which one the annotation scanner emits (STATELESS yields
 * `McpStatelessServerFeatures`), and this test's subject is that the tools register at all, not which
 * transport protocol carries them.
 */
@SpringBootTest
class McpToolRegistrationIntegrationTest {

    @Autowired
    lateinit var context: ApplicationContext

    @Test
    fun `exactly the expected snake_case tools register over MCP HTTP under ASYNC`() {
        val registered = context.getBeansOfType(List::class.java).values
            .flatten()
            .mapNotNull {
                when (it) {
                    is McpServerFeatures.AsyncToolSpecification -> it.tool().name()
                    is McpStatelessServerFeatures.AsyncToolSpecification -> it.tool().name()
                    else -> null
                }
            }
            .toSet()
        assertEquals(
            setOf(
                "search_artifacts", "get_latest_version", "list_versions", "check_version",
                "get_pom", "get_version_catalog",
            ),
            registered,
            "registered async tools should be exactly the expected names",
        )
    }

    @Test
    fun `the primary ArtifactRepository is the failover composite`() {
        // Tools inject ArtifactRepository and must receive the precedence-applying composite.
        assertTrue(context.getBean(ArtifactRepository::class.java) is CompositeArtifactRepository)
    }

    @Test
    fun `Central and the plugin portal are active by default while GitLab is absent`() {
        val backends = context.getBeansOfType(ArtifactBackend::class.java).values
        assertTrue(backends.any { it is MavenCentralArtifactRepository }, "Central backend should be present")
        assertTrue(
            backends.any { it is GradlePluginPortalArtifactRepository },
            "the credential-free plugin portal should be on by default",
        )
        // gitlab.enabled defaults to false (it needs a token), so that backend must NOT be registered.
        assertEquals(2, backends.size, "only Central + portal should be active by default, found: $backends")
    }
}
