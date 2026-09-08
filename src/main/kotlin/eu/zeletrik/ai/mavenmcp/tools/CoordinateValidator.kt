package eu.zeletrik.ai.mavenmcp.tools

import eu.zeletrik.ai.mavenmcp.artifact.ArtifactResult

/**
 * Validates Maven coordinate inputs BEFORE any network call, so malformed input costs nothing and
 * never reaches a request URL.
 *
 * The allowed set is stated positively — ASCII letters, digits, '.', '-', '_', with '.' acting as
 * the groupId package separator — and everything else is rejected by omission, including '/', '\',
 * ':', whitespace, control characters and a '..' traversal sequence. An allowlist is used rather
 * than a blocklist so an unforeseen character cannot slip into a path segment.
 *
 * Returns the [ArtifactResult.ValidationError] to emit, or null when the coordinates are
 * acceptable; it never throws, because the tool layer treats every failure as data.
 */
object CoordinateValidator {

    private val ALLOWED = Regex("^[A-Za-z0-9._-]+$")

    fun validate(groupId: String, artifactId: String): ArtifactResult.ValidationError? =
        check("groupId", groupId) ?: check("artifactId", artifactId)

    private fun check(name: String, value: String): ArtifactResult.ValidationError? = when {
        value.isBlank() ->
            ArtifactResult.ValidationError(name, "$name must not be blank")
        value.contains("..") ->
            ArtifactResult.ValidationError(name, "$name must not contain a '..' sequence")
        !ALLOWED.matches(value) ->
            ArtifactResult.ValidationError(name, "$name may only contain letters, digits, '.', '-', '_'")
        else -> null
    }
}
