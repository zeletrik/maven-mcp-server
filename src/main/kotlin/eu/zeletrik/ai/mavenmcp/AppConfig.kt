package eu.zeletrik.ai.mavenmcp

import eu.zeletrik.ai.mavenmcp.artifact.VersionResolver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

/**
 * Composition-root wiring. The shared [WebClient] is built here, not inside a backend, so the
 * artifact port module stays free of any HTTP dependency and every adapter receives the same client
 * by injection instead of constructing its own.
 */
@Configuration(proxyBeanMethods = false)
class AppConfig {

    /**
     * Built from the AUTOCONFIGURED [WebClient.Builder] rather than `WebClient.builder()`, because
     * that is what carries Boot's observation instrumentation: with a raw builder the calls to
     * Maven Central, GitLab and the plugin portal produce no `http.client.requests` metrics, which
     * are the most interesting numbers this service has to report.
     */
    @Bean
    fun webClient(builder: WebClient.Builder): WebClient = builder.build()

    /** The domain resolver is deliberately Spring-free; it is exposed as a bean only here. */
    @Bean
    fun versionResolver(): VersionResolver = VersionResolver()
}
