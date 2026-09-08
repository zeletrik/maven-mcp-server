package eu.zeletrik.ai.mavenmcp.mavencentral

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Binding for the Maven Central Solr search JSON response. Parsed with Jackson JSON rather than the
 * XML dataformat used for maven-metadata.xml — the two live side by side in this module, so the
 * distinction is worth stating.
 *
 * Only the coordinate fields are read, and unknown properties are ignored so Solr can grow its
 * response without breaking parsing. [SolrDoc.latestVersion] is nullable because the grouped query
 * does not always populate it; a hit with an unknown version is still a hit.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SolrSearchResponse(
    val response: SolrResponseBody = SolrResponseBody(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SolrResponseBody(
    val docs: List<SolrDoc> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SolrDoc(
    val g: String? = null,
    val a: String? = null,
    val latestVersion: String? = null,
)
