package eu.zeletrik.ai.mavenmcp.tools

/**
 * Result of `list_versions`: every published [versions] entry (descending), the [total] count, and
 * the resolved [latestStable] (null when none). [status]/[message] follow the shared convention:
 * `ok` | `not_found` | `source_error` | `validation_error`.
 */
data class ListVersionsResult(
    val groupId: String,
    val artifactId: String,
    val versions: List<String> = emptyList(),
    val total: Int = 0,
    val latestStable: String? = null,
    val status: String,
    val message: String? = null,
)

/**
 * Result of `check_version`. On an existing artifact [exists] is true/false as a normal `ok`
 * result; when the artifact itself is absent, [exists] is null and [status] is `not_found`.
 */
data class CheckVersionResult(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val exists: Boolean? = null,
    val status: String,
    val message: String? = null,
)

/**
 * Result of `get_pom`: the raw unparsed [pomXml] plus the resolved coordinates on `ok`; on the
 * non-ok statuses [pomXml] is null and [message] carries the detail. [version] is the resolved
 * version (e.g. the concrete version that "latest" resolved to), null when resolution failed.
 */
data class PomResult(
    val groupId: String,
    val artifactId: String,
    val version: String? = null,
    val pomXml: String? = null,
    val status: String,
    val message: String? = null,
)

/**
 * Result of `get_version_catalog`: the raw unparsed [toml] of a Gradle version catalog published as
 * a Maven artifact, plus the resolved coordinates. Same status convention as [PomResult]; [version]
 * is the concrete version "latest" resolved to, null when resolution failed.
 */
data class VersionCatalogResult(
    val groupId: String,
    val artifactId: String,
    val version: String? = null,
    val toml: String? = null,
    val status: String,
    val message: String? = null,
)
