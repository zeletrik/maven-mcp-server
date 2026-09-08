package eu.zeletrik.ai.mavenmcp.tools

/**
 * Structured result of `get_latest_version`. [status] communicates the outcome to the MCP client:
 * `ok` (latestVersion populated), `not_found`, `source_error`, or `validation_error`; [message]
 * carries the human-readable detail for the non-ok cases. Errors are conveyed as data, never
 * thrown, so the calling model can react to them instead of seeing a failed tool call.
 */
data class LatestVersionResult(
    val groupId: String,
    val artifactId: String,
    val latestVersion: String? = null,
    val status: String,
    val message: String? = null,
)
