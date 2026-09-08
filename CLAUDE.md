# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

An MCP server (Spring AI 2.0, HTTP/SSE transport only — no STDIO) exposing six tools for Maven
artifact discovery and version/file resolution: `search_artifacts`, `get_latest_version`,
`list_versions`, `check_version`, `get_pom`, `get_version_catalog`. Backends, combined by failover
precedence: Maven Central (always), the Gradle Plugin Portal (on by default — public, no
credentials), and an opt-in GitLab Maven registry.

Stack: Kotlin 2.4 on a Java 25 toolchain, Spring Boot 4.1 WebFlux, Spring Modulith, Jackson 3
(`tools.jackson.*` packages, not `com.fasterxml`), Gradle 9.6.1 with a version catalog
(`gradle/libs.versions.toml`). Actuator exposes `health`, `info` and `prometheus` only.

## Commands

Gradle toolchain auto-detect/auto-download is disabled; Java 25 must come from `JAVA_HOME`, which is
typically unset in the shell. Prefix every Gradle invocation:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew build          # full build + all tests
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew test           # tests only
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew test --tests 'eu.zeletrik.ai.mavenmcp.artifact.VersionResolverTest'   # single class
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew bootRun        # run the server (port 8080)
```

To live-test the GitLab backend, set `gitlab.enabled=true` and export `GITLAB_BASE_URL` (a full
GitLab Maven registry URL) plus `GITLAB_TOKEN`; `GITLAB_SEARCH_URL` is only needed to override the
packages-API URL otherwise derived from the base URL. No registry URL is committed — `application.yaml`
reads those variables, and `gitlab.token` also picks `GITLAB_TOKEN` up via relaxed binding.

## Architecture

Seven Spring Modulith modules under `eu.zeletrik.ai.mavenmcp`, each declared via a
`package-info.java` in `src/main/java` (Kotlin cannot annotate packages) with explicit
`allowedDependencies`:

- **artifact** — domain + port: `ArtifactRepository`, `ArtifactBackend` (marker interface for
  concrete sources), pure `VersionResolver`, sealed `ArtifactResult`, models. Framework-free: its
  only third-party dependency is `org.apache.maven:maven-artifact` (no Spring/WebFlux/Jackson here).
- **mavenrepo** — shared `MavenHttpClient`: fetch + parse `maven-metadata.xml` and raw POMs from any
  Maven-layout repository given a base URL and auth headers. Depends on `artifact` only.
- **mavencentral** — Central backend (`@Order` 0) + Solr keyword search; depends on `artifact` + `mavenrepo`.
- **gitlab** — opt-in GitLab backend (`@Order` 10, `gitlab.enabled`); search uses the GitLab
  Packages API (a different endpoint, derived from `base-url` unless `search-url` is set); depends
  on `artifact` + `mavenrepo`.
- **gradleplugins** — Gradle Plugin Portal backend (`@Order` 20, on by default). Covers Gradle plugin
  **marker** artifacts (`<plugin-id>:<plugin-id>.gradle.plugin`), which Central does not host at all;
  the marker POM names the plugin's implementation coordinates. No search API, so `search` is empty.
- **resolver** — `@Primary CompositeArtifactRepository`, the repository the tools actually inject.
  Version/file lookups use **failover** (first backend that has the artifact wins; a source error
  outranks a not-found when nothing succeeds). `search` **aggregates** instead: it fans out to every
  backend concurrently and interleaves the results round-robin, capped at the limit. De-duplication
  runs first, in precedence order, so interleaving decides position but never which copy wins. It
  used to concatenate and stop once the page was full, which let Maven Central hide internal
  artifacts entirely — the regression tests in `CompositeArtifactRepositoryTest` pin that.
- **tools** — the MCP tool surface: the six `@McpTool` methods, validation, and result→response
  mapping. Depends on `artifact` ONLY — never on a concrete backend (dependency inversion). Named
  `tools` rather than `mcp` because the root package already carries the protocol.

`ModularityTests` runs `ApplicationModules.verify()` and asserts the exact module list — adding a
module means updating that test. `ArchitectureGuardTest` enforces declined dependencies (see below).

### Invariants

- **Sealed result, never throw**: `ArtifactResult` has exactly four variants (Success, NotFound,
  SourceError, ValidationError). When a case does not fit them — an aggregated search where one
  registry answered and another did not — enrich the PAYLOAD, never add a variant: `search` returns
  `SearchOutcome(matches, unavailableSources)` and the tool maps that to a `partial` flag. A fifth
  variant would ripple through every exhaustive `when` in every tool. Tools exhaustively map every variant inside `mono { }` and never
  let an exception or errored `Mono` cross the tool boundary. No partial payloads on error.
- **Coroutines end-to-end, Mono at the rim**: repository/adapter/resolver paths are `suspend` with
  no `.block()`/`runBlocking` in production code (tests may block). `@McpTool` methods cannot be
  `suspend` (Spring AI silently ignores them), so they return `Mono<T>` via the
  `kotlinx.coroutines.reactor.mono { }` builder. `spring.ai.mcp.server.type=ASYNC` is required —
  the default SYNC would silently drop the Mono-returning tools.
- **Version ordering only via `ComparableVersion`** (maven-artifact, version pinned in the catalog
  because no BOM manages it). Never hand-roll a version comparator. Stable = no qualifier or
  GA/RELEASE/FINAL; everything else (alpha, beta, rc, m, cr, pr, ea, SNAPSHOT…) is pre-release;
  classification lives in `VersionResolver` only.
- **Validate then encode**: coordinates are validated against an explicit charset (ASCII letters,
  digits, `.`, `-`, `_`) before any network call, and every path segment is URL-encoded when
  building request URLs (SSRF/traversal guard). Per-version files are addressed via the closed
  `ArtifactFile` enum rather than a caller-supplied extension, so the extension can never carry
  caller input into the URL.
- **Declined dependencies** (test-enforced): no Arrow, no Kotest. Assertions are kotlin-test on
  JUnit 5. XML parsing is Jackson `jackson-dataformat-xml`, never JDK StAX/DOM. The raw POM is
  never parsed.

### Testing conventions

- Tool layer: MockK-mocked `ArtifactRepository`, no HTTP; `StepVerifier` over the returned `Mono`;
  negative paths asserted with `verify(exactly = 0)` on the repository mock.
- Backend adapters: okhttp3 `MockWebServer` 4.x driving the real WebClient path (stay on the legacy
  `com.squareup.okhttp3:mockwebserver` coordinate — `SocketPolicy.NO_RESPONSE` doesn't exist in
  mockwebserver3/okhttp 5).
- `VersionResolver`: plain unit tests, no Spring context.

### Gotchas

- Jackson 3 XML DTOs (`MavenMetadataXml`) use no-arg classes with mutable `var`s (setter binding)
  and `@JacksonXmlElementWrapper(useWrapping = false)` — Kotlin constructor-creator binding clashes
  with `@JacksonXmlProperty` wrapper names and breaks deserialization.
- Modulith's `ApplicationModule` exposes `displayName` (no `name`); iterate `ApplicationModules`
  via `stream()` to avoid Kotlin inference issues.
- The Gradle Plugin Portal **fronts Maven Central**: any coordinate it does not host itself answers
  `303` with an EMPTY body. `MavenHttpClient` would report that as a source error, and failover ranks
  a source error ABOVE a definitive not-found — so without the portal backend's
  `redirectMeansNotFound = true`, every "exists nowhere" lookup would degrade from `not_found` to
  `source_error` across all backends. Following the redirect instead is declined: it would let a
  remote response steer the client off the configured base URL, which is what validating and
  encoding every path segment exists to prevent.
- `MavenHttpClient.fetchText` reads the full `ResponseEntity`, not just the body, so a redirect is
  classified by status — classifying on body text would couple the code to one repository's error
  wording.
- **Boot 4 splits autoconfiguration across many small modules**, so a starter no longer implies the
  autoconfiguration you expect. `spring-boot-starter-webflux` is server-side only: the autoconfigured
  `WebClient.Builder` needs `spring-boot-webclient`, and without it `AppConfig` fails at startup with
  "No qualifying bean of type WebClient$Builder". `WebTestClient`'s auto-configured bean is another
  module again (`spring-boot-webtestclient`) even though the webflux-test starter provides the class.
  When a bean that "should" exist doesn't, look for a missing `spring-boot-<feature>` module before
  suspecting anything else.
- `AppConfig` builds the `WebClient` from the INJECTED `WebClient.Builder`, not `WebClient.builder()`.
  The raw builder carries no observation instrumentation, so outbound calls to the registries would
  produce no `http_client_requests` metrics — the most useful series this service emits, tagged by
  `client_name` per backend.
- The container healthcheck GETs `/actuator/health`. It must NOT GET `/mcp`: BusyBox wget (unlike
  GNU wget) collapses the 405 that endpoint returns, a 404, and connection-refused all to exit 1, so
  such a probe can never distinguish up from down and the container stays permanently unhealthy.
- `.releaserc.yml` must exist and must list plugins explicitly. semantic-release's DEFAULT plugin
  list includes `@semantic-release/npm`, which fails this repo with `ENOPKG Missing package.json` —
  there is no npm package here. The version flows gradle.properties → JAR name → image tag:
  `@semantic-release/exec` stamps it in, `@semantic-release/git` commits it (with `[skip ci]`, since
  the push lands on the branch that triggers the workflow), then the workflow reads it back.
