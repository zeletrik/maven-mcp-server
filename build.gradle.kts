import dev.detekt.gradle.Detekt
import dev.detekt.gradle.plugin.getSupportedKotlinVersion
import org.gradle.kotlin.dsl.withType

plugins {
	alias(libs.plugins.detekt)
	alias(libs.plugins.kotlin.jvm)
	alias(libs.plugins.kotlin.spring)
	alias(libs.plugins.spring.boot)
	alias(libs.plugins.spring.dependency.management)
}

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation(libs.spring.boot.starter.webflux)
	implementation(libs.spring.boot.starter.actuator)
	implementation(libs.spring.boot.webclient)
	// Registry only — the actuator starter exposes /actuator/prometheus once this is present.
	runtimeOnly(libs.micrometer.registry.prometheus)
	implementation(libs.reactor.kotlin.extensions)
	implementation(libs.kotlin.reflect)
	implementation(libs.kotlinx.coroutines.reactor)
	implementation(libs.spring.ai.starter.mcp.server.webflux)
	implementation(libs.spring.modulith.starter.core)
	implementation(libs.jackson.module.kotlin)
	implementation(libs.jackson.dataformat.xml)
	implementation(libs.maven.artifact)
	testImplementation(libs.spring.boot.starter.webflux.test)
	testImplementation(libs.spring.modulith.starter.test)
	testImplementation(libs.kotlin.test.junit5)
	testImplementation(libs.kotlinx.coroutines.test)
	testImplementation(libs.mockk)
	testImplementation(libs.mockwebserver)
	testRuntimeOnly(libs.junit.platform.launcher)
}

dependencyManagement {
	imports {
		mavenBom(libs.spring.ai.bom.get().toString())
		mavenBom(libs.spring.modulith.bom.get().toString())
	}
	configurations.matching { it.name == "detekt" }.all {
		resolutionStrategy.eachDependency {
			if (requested.group == "org.jetbrains.kotlin") {
				useVersion(getSupportedKotlinVersion())
			}
		}
	}
}

detekt {
	config.setFrom(file("detekt.yml"))
	buildUponDefaultConfig = true
	autoCorrect = true
}

tasks.withType<Detekt>().configureEach {
	reports {
		checkstyle.required.set(true)
		html.required.set(true)
		sarif.required.set(true)
		markdown.required.set(false)
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}
