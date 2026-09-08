# Contributing

Thanks for looking. This is a small project with a few opinions baked in; the ones worth knowing
before you write code are below.

## Getting a build

The Gradle toolchain is pinned to Java 25 with auto-detection and auto-download **disabled**, so
`JAVA_HOME` has to point at a JDK 25 yourself:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew build          # compile, test, lint
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew test detekt    # the two gates CI runs
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew bootRun        # serves on :8080
```

A running server speaks MCP over HTTP at `/mcp`. `GET` returns 405 — it only accepts `POST` — which
is a quick way to confirm it is up:

```sh
curl -s -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -X POST http://localhost:8080/mcp \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```

## Commit messages decide releases

Releases are automatic. `semantic-release` reads [Conventional Commits](https://www.conventionalcommits.org)
on `main`, works out the next version, writes it into `gradle.properties`, tags, and publishes the
Docker image. So the prefix you choose is a release decision:

| Prefix | Effect |
|---|---|
| `fix:` | patch release |
| `feat:` | minor release |
| `feat!:` or a `BREAKING CHANGE:` footer | major release |
| `chore:`, `docs:`, `refactor:`, `test:` | no release |

## Design rules that are not negotiable

These are enforced by tests, and a change that breaks one will fail the build rather than get
caught in review. Each exists for a reason worth knowing:

- **Never throw across the tool boundary.** `ArtifactResult` has exactly four variants and every
  `@McpTool` method maps all of them to a structured response. An exception escaping would surface
  to the caller as a failed tool call instead of an answer it can act on. Error results carry no
  partial payload either — half an answer presented as an answer is worse than a clean error.
- **`not_found` and `source_error` are different.** The first means "this does not exist", the
  second means "I could not check". Conflating them is how an agent talks someone out of a
  dependency that is fine. When backends disagree, an inconclusive error deliberately outranks a
  definitive miss.
- **Version ordering goes through `ComparableVersion`.** Never hand-roll a comparator, and never
  sort version strings. `1.10` is above `1.9`, and the qualifier rules have more corners than they
  appear to. Stable-versus-pre-release classification lives in `VersionResolver` alone so the two
  definitions cannot drift.
- **Validate, then encode.** Coordinates are checked against an allowlist before any network call,
  and every path segment is URL-encoded. Per-version files are addressed through the closed
  `ArtifactFile` enum, never a caller-supplied extension, so caller input cannot reach a URL.
- **Module boundaries are verified.** Seven Spring Modulith modules, each declaring its
  `allowedDependencies` in a `package-info.java`. The tool layer depends on the `artifact` port only
  and never on a concrete backend. Adding a module means updating `ModularityTests`.
- **Declined dependencies.** No Arrow (the sealed result *is* the error model) and no Kotest (one
  assertion style: kotlin-test on JUnit 5). `ArchitectureGuardTest` fails if either appears.

## Testing conventions

- **Tool layer**: MockK-mocked `ArtifactRepository`, no HTTP. Assert negative paths with
  `coVerify(exactly = 0)` — checking only the returned status would pass an implementation that
  validated *after* firing the request.
- **Backend adapters**: okhttp `MockWebServer` driving the real WebClient path, so URL construction
  and status classification are genuinely exercised. Assert the recorded request path too; a
  silently wrong URL otherwise looks identical to a missing artifact.
- **`VersionResolver`**: plain unit tests, no Spring context. Exercise every qualifier rather than a
  representative one — each is an independent string match, so getting `rc` right says nothing
  about `cr` or `ea`.
- Do not add tests for framework behaviour. Actuator endpoints returning 200 is Spring's problem,
  not ours.

## Adding a backend

The `ArtifactBackend` interface plus a `@Order` value is the whole contract; `mavenrepo`'s
`MavenHttpClient` already handles fetching, encoding and status classification for anything with a
Maven layout. Look at `gradleplugins` for the smallest example.

Two things that have bitten before. A repository that *fronts* another one may answer `3xx` for
coordinates it does not host — map that to not-found, or the composite's failover will rank it as a
source error and degrade unrelated lookups. And Spring Boot 4 splits autoconfiguration across many
small modules, so a bean you expect from a starter may need its own `spring-boot-<feature>`
dependency.

## Pull requests

Keep `./gradlew build detekt` green, add a test that fails without your change, and say in the
description what you verified and how. If you touched behaviour a caller depends on, check whether
`README.md` still describes it accurately.
