package eu.zeletrik.ai.mavenmcp.artifact

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Full VersionResolver coverage: latest-stable and latest-including-prerelease resolution, strict
 * descending order, the no-stable outcome, and stable/pre-release classification across the COMPLETE
 * qualifier set.
 *
 * Every qualifier is exercised rather than a representative one, because each is an independent
 * string match — getting `rc` right says nothing about `cr` or `ea`. The ordering assertions use
 * versions where numeric and lexical order disagree (e.g. 10.0.0 vs 9.0.0), so a lexical comparator
 * sneaking in would fail rather than pass by coincidence.
 */
class VersionResolverTest {

    private val resolver = VersionResolver()

    @Test
    fun `latestStable picks the highest stable, ignoring a higher pre-release`() {
        assertEquals("1.1.0", resolver.latestStable(listOf("1.0.0", "1.1.0", "2.0.0-RC1")))
    }

    @Test
    fun `latestStable orders numerically, not lexically`() {
        assertEquals("1.10.0", resolver.latestStable(listOf("1.9.0", "1.10.0", "1.2.0")))
    }

    @Test
    fun `latestStable is null when only pre-releases exist`() {
        assertNull(resolver.latestStable(listOf("1.0.0-alpha1", "1.0.0-SNAPSHOT", "2.0.0-M2")))
    }

    @Test
    fun `latest includes pre-releases and returns the highest overall`() {
        assertEquals("2.0.0-RC1", resolver.latest(listOf("1.0.0", "1.1.0", "2.0.0-RC1")))
    }

    @Test
    fun `latest is null for an empty list`() {
        assertNull(resolver.latest(emptyList()))
    }

    @Test
    fun `sortedDescending orders newest first by Maven version ordering`() {
        assertEquals(
            listOf("2.0.0-RC1", "1.10.0", "1.2.0", "1.0.0"),
            resolver.sortedDescending(listOf("1.0.0", "2.0.0-RC1", "1.10.0", "1.2.0")),
        )
    }

    @Test
    fun `stable forms classify as stable`() {
        listOf("2.0.0", "1.0.0.GA", "1.0.0.RELEASE", "1.0.0.FINAL", "10.0.0").forEach {
            assertTrue(resolver.isStable(it), "$it should be stable")
        }
    }

    @Test
    fun `every pre-release qualifier classifies as pre-release`() {
        listOf(
            "1.0.0-alpha", "1.0.0-beta", "1.0.0-rc1", "1.0.0-m2", "1.0.0-milestone1",
            "1.0.0-cr1", "1.0.0-pr1", "1.0.0-ea", "1.0.0-snapshot", "1.0.0-SNAPSHOT",
        ).forEach {
            assertFalse(resolver.isStable(it), "$it should be pre-release")
        }
    }

    @Test
    fun `latestStable prefers a lower stable over a higher pre-release (metadata-tag scenario)`() {
        // Mirrors maven-metadata.xml whose <latest> points at a pre-release: the resolver must
        // still return the highest STABLE version from the <versions> list.
        assertEquals("1.1.0", resolver.latestStable(listOf("2.0.0-RC1", "1.1.0")))
    }
}
