package eu.zeletrik.ai.mavenmcp.mavencentral

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import java.time.Duration
import kotlin.test.assertEquals

/**
 * Verifies MavenCentralProperties binds its three values from application.yaml, and that the MCP
 * server is configured in ASYNC mode — under the default SYNC the annotation scanner silently
 * ignores Mono-returning tool methods, so the server would start with no tools at all.
 */
@SpringBootTest
class MavenCentralConfigTest {

    @Autowired
    lateinit var properties: MavenCentralProperties

    @Autowired
    lateinit var environment: Environment

    @Test
    fun `MavenCentralProperties binds base URLs and timeout from application yaml`() {
        assertEquals("https://repo1.maven.org/maven2/", properties.metadataBaseUrl)
        assertEquals("https://central.sonatype.com/solrsearch/select", properties.searchBaseUrl)
        assertEquals(Duration.ofSeconds(5), properties.timeout)
    }

    @Test
    fun `MCP server type resolves to ASYNC`() {
        assertEquals("ASYNC", environment.getProperty("spring.ai.mcp.server.type"))
    }
}
