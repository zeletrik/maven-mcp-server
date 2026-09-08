package eu.zeletrik.ai.mavenmcp.tools

/**
 * Result of `search_artifacts`: the matching [results] (an empty list on no matches, never an
 * error), echoing the [query]. [status]/[message] follow the shared convention.
 */
data class SearchArtifactsResult(
    val query: String,
    val results: List<SearchMatch> = emptyList(),
    val status: String,
    val message: String? = null,
)

/** A single search hit: the coordinates plus the latest version, and deliberately nothing more. */
data class SearchMatch(
    val groupId: String,
    val artifactId: String,
    val latestVersion: String?,
)
