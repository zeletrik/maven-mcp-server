/**
 * Driving-adapter module: the MCP tool surface. Holds the {@code @McpTool} methods, their result
 * DTOs, input validation, and the domain-result-to-tool-response mapping. Depends on the artifact
 * port ONLY and never on a concrete backend, so backends can be added or swapped without touching
 * this layer. Declared as an explicit application module so that inversion is verified by the build.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"artifact"})
package eu.zeletrik.ai.mavenmcp.tools;
