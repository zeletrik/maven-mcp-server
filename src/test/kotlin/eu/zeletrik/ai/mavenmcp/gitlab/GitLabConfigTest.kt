package eu.zeletrik.ai.mavenmcp.gitlab

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * Guards the shipped GitLab configuration contract: the backend is opt-in, and no registry URL is
 * committed — both come from the environment. `@ConfigurationPropertiesScan` binds the properties
 * even while the backend is disabled, so [GitLabProperties] is injectable from the default context.
 *
 * The placeholder assertions read the yaml rather than the bound values on purpose: a misspelled
 * variable name (`GIRLAB_BASE_URL`) still resolves to "" through the `:` default, so the backend
 * would go silently unconfigured with nothing to observe on the bound property.
 */
@SpringBootTest
class GitLabConfigTest {

    @Autowired
    lateinit var properties: GitLabProperties

    @Test
    fun `the backend ships opt-in`() {
        assertFalse(properties.enabled, "the GitLab backend must stay disabled until explicitly enabled")
    }

    @Test
    fun `the registry URLs come from the environment, not from committed values`() {
        val yaml = checkNotNull(javaClass.getResource("/application.yaml")).readText()
        assertContains(yaml, "base-url: \${GITLAB_BASE_URL:}", message = "base-url must read GITLAB_BASE_URL")
        assertContains(yaml, "search-url: \${GITLAB_SEARCH_URL:}", message = "search-url must read GITLAB_SEARCH_URL")
    }
}
