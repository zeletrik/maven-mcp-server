package eu.zeletrik.ai.mavenmcp.artifact

/**
 * Maven coordinates identifying an artifact. Version-level operations take a [version] separately.
 */
data class Coordinates(val groupId: String, val artifactId: String)

/**
 * The version set published for an artifact, as read from its maven-metadata.xml. Only the
 * `<versions>` list is modeled; the metadata `<latest>`/`<release>` tags are intentionally NOT
 * carried. Those tags are written by whichever tool last published and are frequently stale or
 * absent, so "latest" is always recomputed from this list via ComparableVersion instead.
 */
data class ArtifactMetadata(val coordinates: Coordinates, val versions: List<String>)

/**
 * A single artifact match from a keyword search. [latestVersion] is nullable because search
 * backends do not reliably populate it — a match with an unknown latest version is still a useful
 * match, so a missing value must never be escalated into an error.
 */
data class ArtifactMatch(val groupId: String, val artifactId: String, val latestVersion: String?)
