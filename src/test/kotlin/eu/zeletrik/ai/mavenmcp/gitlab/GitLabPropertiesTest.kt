package eu.zeletrik.ai.mavenmcp.gitlab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The token is sent under the configured header, with Bearer prefixing for OAuth; no token = no headers. */
class GitLabPropertiesTest {

    @Test
    fun `private token header carries the raw token`() {
        val headers = GitLabProperties(
            token = "abc",
            tokenHeader = GitLabProperties.TokenHeader.PRIVATE_TOKEN,
        ).authHeaders()
        assertEquals(mapOf("Private-Token" to "abc"), headers)
    }

    @Test
    fun `bearer header prefixes the token`() {
        val headers = GitLabProperties(token = "abc", tokenHeader = GitLabProperties.TokenHeader.BEARER).authHeaders()
        assertEquals(mapOf("Authorization" to "Bearer abc"), headers)
    }

    @Test
    fun `no token yields no auth headers`() {
        assertTrue(GitLabProperties(token = null).authHeaders().isEmpty())
        assertTrue(GitLabProperties(token = "  ").authHeaders().isEmpty())
    }

    @Test
    fun `a blank search-url derives the packages API from the base-url`() {
        // searchUrl defaults to blank precisely so a programmatically supplied baseUrl stays in step.
        assertEquals(
            "https://gitlab.example.com/api/v4/groups/45/packages",
            GitLabProperties(baseUrl = "https://gitlab.example.com/api/v4/groups/45/-/packages/maven")
                .effectiveSearchUrl(),
        )
        assertEquals(
            "https://gitlab.example.com/api/v4/projects/7/packages",
            GitLabProperties(baseUrl = "https://gitlab.example.com/api/v4/projects/7/packages/maven")
                .effectiveSearchUrl(),
        )
    }

    @Test
    fun `an explicit search-url wins over derivation`() {
        val properties = GitLabProperties(
            baseUrl = "https://gitlab.example.com/api/v4/groups/45/-/packages/maven",
            searchUrl = "https://gitlab.example.com/api/v4/groups/99/packages",
        )
        assertEquals("https://gitlab.example.com/api/v4/groups/99/packages", properties.effectiveSearchUrl())
    }
}
