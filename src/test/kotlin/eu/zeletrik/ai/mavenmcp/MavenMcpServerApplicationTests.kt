package eu.zeletrik.ai.mavenmcp

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import kotlin.test.assertTrue

/**
 * Smoke test: the whole application context wires up. Any bean that cannot be constructed fails
 * before the assertion runs, which is the point — this catches wiring breakage that no unit test
 * touching a single class would notice.
 */
@SpringBootTest
class MavenMcpServerApplicationTests {

    @Autowired
    lateinit var context: ApplicationContext

    @Test
    fun `the application context starts`() {
        assertTrue(context.beanDefinitionCount > 0, "a started context must expose bean definitions")
    }
}
