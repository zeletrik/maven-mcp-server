package eu.zeletrik.ai.mavenmcp.gitlab

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * GitLab Maven registry configuration (opt-in). Set [enabled] true and supply [baseUrl] and a
 * [token]; both are sourced from the environment (`GITLAB_BASE_URL`, `GITLAB_TOKEN`) rather than
 * committed, so no deployment-specific registry is baked into the build. [baseUrl] is the full Maven
 * registry URL for the desired level, e.g.
 * `https://gitlab.example.com/api/v4/projects/123/packages/maven` (project),
 * `.../groups/45/-/packages/maven` (group), or `.../packages/maven` (instance) — the level is
 * encoded in the URL, so no separate enum is needed. The id may be numeric or a URL-encoded path.
 *
 * [token] authenticates read access to private registries; it may reference an environment variable
 * via a Spring placeholder (e.g. `${GITLAB_TOKEN}`). [tokenHeader] selects how the token is sent.
 *
 * For keyword search the GitLab **Packages API** (`/groups/:id/packages` or `/projects/:id/packages`)
 * is used, which differs from the Maven registry [baseUrl]. [searchUrl] is BLANK by default on
 * purpose — blank means "derive from [baseUrl]" (see [effectiveSearchUrl]), which keeps the two in
 * step; set `GITLAB_SEARCH_URL` only to override that derivation.
 *
 * @property enabled whether the GitLab backend participates in resolution.
 * @property baseUrl full GitLab Maven registry base URL; blank until configured.
 * @property searchUrl GitLab Packages API base URL for search; derived from [baseUrl] when blank.
 * @property token read token, or null for a public registry.
 * @property tokenHeader how to present the token.
 * @property timeout bounded connect + response timeout for GitLab requests.
 */
private const val DEFAULT_TIMEOUT_SECONDS = 5L

@ConfigurationProperties(prefix = "gitlab")
data class GitLabProperties(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val searchUrl: String = "",
    val token: String? = null,
    val tokenHeader: TokenHeader = TokenHeader.PRIVATE_TOKEN,
    val timeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
) {
    /** The HTTP header the [token] is sent in (see GitLab package-registry auth options). */
    enum class TokenHeader(val headerName: String, val bearer: Boolean) {
        PRIVATE_TOKEN("Private-Token", false),
        DEPLOY_TOKEN("Deploy-Token", false),
        JOB_TOKEN("Job-Token", false),
        BEARER("Authorization", true),
    }

    /** The auth headers to send, or empty when no token is configured. */
    fun authHeaders(): Map<String, String> {
        val value = token?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return mapOf(tokenHeader.headerName to if (tokenHeader.bearer) "Bearer $value" else value)
    }

    /**
     * The GitLab Packages API base URL used for search: [searchUrl] if set, else derived from
     * [baseUrl]; blank when neither is available (search then contributes nothing).
     */
    fun effectiveSearchUrl(): String = when {
        searchUrl.isNotBlank() -> searchUrl
        baseUrl.endsWith(GROUP_MAVEN_SUFFIX) -> baseUrl.removeSuffix(GROUP_MAVEN_SUFFIX) + PACKAGES_SUFFIX
        baseUrl.endsWith(PROJECT_MAVEN_SUFFIX) -> baseUrl.removeSuffix(PROJECT_MAVEN_SUFFIX) + PACKAGES_SUFFIX
        else -> ""
    }

    private companion object {
        const val GROUP_MAVEN_SUFFIX = "/-/packages/maven"
        const val PROJECT_MAVEN_SUFFIX = "/packages/maven"
        const val PACKAGES_SUFFIX = "/packages"
    }
}
