package eu.zeletrik.ai.mavenmcp.artifact

import org.apache.maven.artifact.versioning.ComparableVersion

/**
 * Pure version resolution, with no Spring, HTTP or Jackson coupling so it stays unit-testable in
 * isolation. All ordering goes through Maven's own [ComparableVersion]; a hand-rolled comparator is
 * deliberately avoided, since version ordering has far more edge cases than it appears to.
 *
 * This class is the ONLY place that decides what counts as stable versus pre-release, so the two
 * definitions cannot drift apart across the tools that need them.
 */
class VersionResolver {

    /**
     * A version is stable unless one of its qualifier tokens is a recognized pre-release marker
     * (alpha, beta, rc, m/milestone, cr, pr, ea, snapshot — optionally followed by digits, e.g.
     * `rc1`). Absent qualifier and GA/RELEASE/FINAL are stable.
     */
    fun isStable(version: String): Boolean =
        version.lowercase().split('.', '-', '_', '+').none(PRERELEASE_TOKEN::matches)

    /** The highest stable version by Maven ordering, or `null` when no stable version exists. */
    fun latestStable(versions: List<String>): String? =
        versions.filter(::isStable).maxByOrNull(::ComparableVersion)

    /**
     * The highest version by Maven ordering including pre-releases, or `null` when [versions] is
     * empty. Used for the opt-in `prerelease=true` path.
     */
    fun latest(versions: List<String>): String? =
        versions.maxByOrNull(::ComparableVersion)

    /** All [versions] ordered strictly descending by Maven ordering (newest first). */
    fun sortedDescending(versions: List<String>): List<String> =
        versions.sortedByDescending(::ComparableVersion)

    private companion object {
        val PRERELEASE_TOKEN = Regex("^(alpha|beta|rc|m|milestone|cr|pr|ea|snapshot)[0-9]*$")
    }
}
