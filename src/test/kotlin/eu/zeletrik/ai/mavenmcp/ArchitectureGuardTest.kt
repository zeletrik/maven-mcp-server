package eu.zeletrik.ai.mavenmcp

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Guards two deliberately declined dependencies. Arrow is declined because the error model is the
 * hand-written sealed [eu.zeletrik.ai.mavenmcp.artifact.ArtifactResult] and a second
 * `Either`-style abstraction alongside it would fragment error handling; Kotest is declined to keep
 * one assertion style (kotlin-test on JUnit 5) across every source set.
 *
 * Both are proven absent by their marker types failing to load even on the test classpath, which is
 * the widest classpath in the build. Dependency SCOPING (mockk and mockwebserver staying test-only,
 * the pinned maven-artifact version) is enforced in build.gradle.kts and the version catalog.
 */
class ArchitectureGuardTest {

    @Test
    fun `Arrow is absent from every source set`() {
        assertFailsWith<ClassNotFoundException> { Class.forName("arrow.core.Either") }
    }

    @Test
    fun `Kotest is absent from every source set`() {
        assertFailsWith<ClassNotFoundException> { Class.forName("io.kotest.core.spec.Spec") }
    }
}
