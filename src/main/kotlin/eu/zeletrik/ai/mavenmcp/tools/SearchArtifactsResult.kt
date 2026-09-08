package eu.zeletrik.ai.mavenmcp.tools

/**
 * Result of `search_artifacts`: the matching [results] (an empty list on no matches, never an
 * error), echoing the [query]. [status]/[message] follow the shared convention.
 *
 * [partial] is specific to search, because search is the only aggregate: it is `true` when at least
 * one registry could not be reached, so the results are usable but incomplete. The status stays
 * `ok` — the call did succeed and the matches are real — but without this flag a short list from a
 * degraded registry is indistinguishable from a query that genuinely matched little, and a caller
 * would wrongly conclude an artifact does not exist. [message] names what was unavailable.
 */
data class SearchArtifactsResult(
    val query: String,
    val results: List<SearchMatch> = emptyList(),
    val status: String,
    val message: String? = null,
    val partial: Boolean = false,
)

/** A single search hit: the coordinates plus the latest version, and deliberately nothing more. */
data class SearchMatch(
    val groupId: String,
    val artifactId: String,
    val latestVersion: String?,
)
