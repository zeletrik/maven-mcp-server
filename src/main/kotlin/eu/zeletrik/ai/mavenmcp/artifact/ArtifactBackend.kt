package eu.zeletrik.ai.mavenmcp.artifact

/**
 * Marker for a concrete artifact source (Maven Central, GitLab, …). Distinguishing backends from
 * the aggregating [ArtifactRepository] the tools consume lets the composite inject the ordered list
 * of backends without injecting itself. Backends are ordered by Spring `@Order` (lowest first);
 * the composite consults them by precedence.
 */
interface ArtifactBackend : ArtifactRepository
