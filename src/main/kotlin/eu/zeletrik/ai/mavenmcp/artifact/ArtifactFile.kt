package eu.zeletrik.ai.mavenmcp.artifact

/**
 * The per-version files a Maven-layout repository publishes alongside the binary, addressed as
 * `…/{group}/{artifactId}/{version}/{artifactId}-{version}.{extension}`.
 *
 * A closed enum rather than a caller-supplied extension string: the extension is interpolated into
 * the outbound URL, and keeping the set closed means it can never carry caller input there. Together
 * with coordinate validation this is what keeps request paths inside the configured base URL.
 */
enum class ArtifactFile(val extension: String) {

    /** The POM, published for every artifact regardless of packaging. */
    POM("pom"),

    /** A Gradle version catalog, published by the `version-catalog` plugin. */
    VERSION_CATALOG("toml"),
}
