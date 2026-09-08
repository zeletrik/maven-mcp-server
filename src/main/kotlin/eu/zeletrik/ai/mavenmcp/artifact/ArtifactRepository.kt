package eu.zeletrik.ai.mavenmcp.artifact

/**
 * The single port through which every artifact source is reached, so the tool layer never depends
 * on a concrete backend. Implementations are `suspend` and non-blocking end-to-end.
 *
 * Implementations map transport and parse outcomes onto the [ArtifactResult] variants and NEVER
 * throw across this boundary — a failure is data, so callers can always answer with a structured
 * result. See [eu.zeletrik.ai.mavenmcp.resolver.CompositeArtifactRepository] for the
 * implementation the tools actually receive, which fans out across the configured backends.
 */
interface ArtifactRepository {

    /**
     * Fetch the published version set for [coordinates] from the source's metadata.
     * Returns [ArtifactResult.NotFound] when the artifact has no metadata, or
     * [ArtifactResult.SourceError] when the source is unreachable/timed-out/unparseable.
     */
    suspend fun fetchVersions(coordinates: Coordinates): ArtifactResult<ArtifactMetadata>

    /**
     * Fetch the raw, unparsed body of [file] for [coordinates] at [version].
     * Returns [ArtifactResult.NotFound] when that version does not publish the file, or
     * [ArtifactResult.SourceError] on transport failure/timeout. Bodies are returned verbatim and
     * are never parsed, so nothing is lost or reinterpreted on the way to the caller.
     */
    suspend fun fetchFile(
        coordinates: Coordinates,
        version: String,
        file: ArtifactFile,
    ): ArtifactResult<String>

    /** Convenience for the POM, the most frequently requested [ArtifactFile]. */
    suspend fun fetchPom(coordinates: Coordinates, version: String): ArtifactResult<String> =
        fetchFile(coordinates, version, ArtifactFile.POM)

    /**
     * Search the source for artifacts matching [query], returning at most [limit] matches.
     * [query] is used as the search term as-is. An empty match set is a successful empty list,
     * not [ArtifactResult.NotFound].
     */
    suspend fun search(query: String, limit: Int): ArtifactResult<SearchOutcome>
}
