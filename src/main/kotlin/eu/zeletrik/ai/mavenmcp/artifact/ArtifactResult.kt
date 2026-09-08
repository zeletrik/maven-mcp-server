package eu.zeletrik.ai.mavenmcp.artifact

/**
 * The closed contract returned by [ArtifactRepository] and the resolver layer: exactly four
 * variants, so a `when` over them is exhaustive and adding a fifth becomes a compile error at every
 * call site rather than a silently unhandled case.
 *
 * Tools map each variant to a structured MCP response and never throw across the boundary, so a
 * client always receives an answer it can act on. Error variants extend `ArtifactResult<Nothing>`,
 * which lets them satisfy any requested payload type without a cast.
 */
sealed interface ArtifactResult<out T> {

    /** The operation succeeded and carries its [value]. */
    data class Success<out T>(val value: T) : ArtifactResult<T>

    /** The requested artifact/version/POM does not exist on the source. */
    data class NotFound(val message: String) : ArtifactResult<Nothing>

    /** The source was unreachable, timed out, or returned unparseable data. */
    data class SourceError(val message: String) : ArtifactResult<Nothing>

    /** The caller's input was rejected before any source access; [parameter] names the offender. */
    data class ValidationError(val parameter: String, val message: String) : ArtifactResult<Nothing>
}
