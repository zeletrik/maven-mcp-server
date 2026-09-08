package eu.zeletrik.ai.mavenmcp

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class MavenMcpServerApplication

fun main(args: Array<String>) {
	runApplication<MavenMcpServerApplication>(*args)
}
