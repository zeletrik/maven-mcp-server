package eu.zeletrik.ai.mavenmcp.mavenrepo

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonRootName
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty

/**
 * Binding for maven-metadata.xml (shared across all Maven-layout backends). Only
 * `<versioning><versions><version>` is read; `<latest>`/`<release>` are deliberately ignored
 * because they are written by whichever tool last published and are often stale or missing. Latest
 * is recomputed from the version list via ComparableVersion instead.
 *
 * Modeled as plain no-arg classes with mutable properties so Jackson uses setter/field binding
 * (not Kotlin constructor-creator binding), mirroring the XML one-to-one with `useWrapping = false`
 * to avoid the wrapper-vs-item localName / creator-property rename clash constructor binding causes.
 */
@JsonRootName("metadata")
@JsonIgnoreProperties(ignoreUnknown = true)
class MavenMetadataXml {
    var versioning: Versioning? = null

    @JsonIgnoreProperties(ignoreUnknown = true)
    class Versioning {
        var versions: Versions? = null
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    class Versions {
        @JacksonXmlProperty(localName = "version")
        @JacksonXmlElementWrapper(useWrapping = false)
        var version: List<String> = emptyList()
    }
}
