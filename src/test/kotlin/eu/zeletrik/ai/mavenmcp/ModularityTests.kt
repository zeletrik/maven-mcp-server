package eu.zeletrik.ai.mavenmcp

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import kotlin.test.assertEquals

/**
 * Enforces the module boundaries: exactly the expected application modules, no dependency cycles,
 * and no path from the tool layer to a concrete backend. [ApplicationModules.verify] performs the
 * boundary and cycle checks, so a violation fails the build rather than surviving as convention.
 *
 * The explicit module list means ADDING a module is a deliberate act: a new package that declares
 * itself a module fails this test until it is listed here.
 */
class ModularityTests {

    private val modules = ApplicationModules.of(MavenMcpServerApplication::class.java)

    @Test
    fun `module structure verifies with no boundary violations or cycles`() {
        modules.verify()
    }

    @Test
    fun `exactly the expected application modules are present`() {
        val names = modules.stream().map { it.displayName.lowercase() }.sorted().toList()
        assertEquals(
            listOf("artifact", "gitlab", "gradleplugins", "mavencentral", "mavenrepo", "resolver", "tools"),
            names,
            "detected modules: $names",
        )
    }
}
