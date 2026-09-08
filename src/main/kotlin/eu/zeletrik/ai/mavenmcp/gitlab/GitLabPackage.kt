package eu.zeletrik.ai.mavenmcp.gitlab

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * A row from the GitLab Packages API (`package_type=maven`). [name] is the Maven package path,
 * e.g. `com/example/commons/spring-boot-starter-security` (last segment = artifactId,
 * the rest = groupId with '.'); [version] is one published version (the API returns one row per
 * version). Only these fields are read.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GitLabPackage(
    val name: String? = null,
    val version: String? = null,
)
