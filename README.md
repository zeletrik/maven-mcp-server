# maven-mcp-server

An [MCP](https://modelcontextprotocol.io) server that answers Maven artifact questions: find
artifacts by keyword, resolve the latest version, list every published version, check whether a
specific version exists, and fetch a POM or a Gradle version catalog.

It exists because "what's the current version of X?" is a question language models get wrong
constantly — training data goes stale, and a guessed version number produces a build that fails for
reasons unrelated to the code. This server answers from the registry instead.

## Tools

| Tool | Answers |
|---|---|
| `search_artifacts` | Artifacts matching a keyword query, with their latest versions. |
| `get_latest_version` | The latest **stable** version; `prerelease=true` includes pre-releases. |
| `list_versions` | Every published version, newest first, with a total and the latest stable. |
| `check_version` | Whether one specific version is published. |
| `get_pom` | The raw POM XML for a version, or for the literal `"latest"`. |
| `get_version_catalog` | The raw TOML of a Gradle version catalog published as a Maven artifact. |

"Latest" means the highest **stable** version by Maven's own ordering — pre-release qualifiers
(`alpha`, `beta`, `rc`, `m`/`milestone`, `cr`, `pr`, `ea`, `SNAPSHOT`) are excluded unless you ask
for them. A missing stable release is reported as such rather than silently downgraded to a
pre-release.

### Gradle plugins

Gradle plugins publish a *marker* artifact, so a plugin id maps to coordinates mechanically: plugin
`org.sonarqube` lives at groupId `org.sonarqube`, artifactId `org.sonarqube.gradle.plugin`. Those
resolve from the Gradle Plugin Portal, which frequently carries releases Maven Central does not. The
marker POM names the plugin's implementation artifact.

## Why a server, rather than curl or a CLI wrapper

An agent with shell access can already `curl` a `maven-metadata.xml`, and a skill can already wrap
`glab`. Both work. This exists because of what they get wrong at the margins, and the margins are
where a wrong version number costs you a broken build.

### "Latest" is harder than it looks

`maven-metadata.xml` carries `<latest>` and `<release>` tags, and they are the obvious things to
read. They track the most recent *upload*, though — `<release>` excludes `SNAPSHOT`, but nothing
else, so a milestone or release candidate satisfies it. Every obvious strategy therefore agrees on
the wrong answer. For `org.springframework.boot:spring-boot` at the time of writing:

| Approach                          | Answer      |
|-----------------------------------|-------------|
| Trust `<latest>`                  | `4.2.0-M1`  |
| Trust `<release>`                 | `4.2.0-M1`  |
| Take the last `<version>` element | `4.2.0-M1`  |
| Sort the version strings          | `4.2.0-M1`  |
| `get_latest_version`              | **`4.1.1`** |

Four independent naive readings, one milestone build, and a plausible-looking number that a model
will state with complete confidence. This server ignores both metadata tags, orders versions with
Maven's own `ComparableVersion` — string sorting also puts `1.9` above `1.10` — and filters
pre-release qualifiers unless you ask for them. When only pre-releases exist it says so rather than
quietly returning one.

### One question can need several registries

Asking for the latest SonarQube Gradle plugin against Maven Central returns `3.3`, because that is
where its implementation artifact stopped being published. The live version is `7.5.0.8588`, on the
Gradle Plugin Portal, under a plugin marker coordinate Central does not host at all. An agent
curling Central gets a real answer from a real registry that is off by four major versions.

Getting that right by hand means knowing which registry to try, in what order, under which coordinate
convention, and how to treat a miss in one as "keep looking" rather than "does not exist". That
precedence logic is what this server is.

### The credential stays out of the agent's reach

A skill around `glab` hands the agent a shell and an authenticated CLI: it can delete a package, push
a tag, or open a merge request, and the token is one `env` away. Here the token lives in the server's
environment and the agent gets six read-only tools. The reachable capability *is* the tool list —
coordinates are validated against an allowlist and file extensions come from a closed enum, so
requests cannot be steered off the configured registries either.

### It answers in a line, not a page

`kotlin-stdlib`'s metadata is ~10 KB of XML across 301 versions. Answering "what version should I
use?" from that means pulling all of it into context and parsing it there, every time. The same
question through `get_latest_version` is one line. The difference compounds across a dependency
review.

### Measured: it is faster too, which is not the obvious result

The natural assumption is that a tool call must be slower — it is an extra hop, and the server does
the same fetch you would have done. It measures the other way round. Auditing this project's own ten
dependencies:

|                    | MCP tool calls | `curl` per lookup |
|--------------------|----------------|-------------------|
| wall clock         | **523 ms**     | 1186 ms           |
| bytes into context | **2.3 KB**     | 55.5 KB           |
| approx. tokens     | **~565**       | ~13,900           |

Per lookup that is 52 ms against 119 ms. The reason is not clever code, it is connection reuse —
decomposing a single Spring Boot lookup on the same machine:

|                                          | median |
|------------------------------------------|--------|
| localhost round trip (floor)             | 12 ms  |
| pooled HTTPS to the registry             | 42 ms  |
| TLS handshake, paid again per shell call | +56 ms |
| process spawn, paid again per `curl`     | +30 ms |

The server keeps a warm pooled connection to each registry, so it pays the handshake once and
amortises it over every later call. A shell invocation cannot: each one is a fresh process and a
fresh TLS session. That is also why the gap widens with volume rather than narrowing.

Two things this comparison deliberately does **not** do, both of which favour the `curl` side. It
excludes the model's own reasoning time, even though the 55 KB of XML still has to be read and
reduced to ten version numbers by the model. And it gives the DIY path a *correct* pre-release
filter and Maven-ordering implementation for free — that is the gap described above, held constant
here so this measures mechanics alone. Numbers are medians from one machine and network; treat the
ratios as the finding, not the milliseconds.

### "Not found" and "could not check" stay distinct

Every tool returns one of four statuses, and `not_found` is never conflated with `source_error`. A
registry that is down, slow, or rejecting a token must not be reported as "that version does not
exist" — that is how an agent talks someone out of a dependency that is fine. When several backends
are consulted, an inconclusive error deliberately outranks a definitive miss.

### Where the alternatives are the better choice

- **You need to *do* something in GitLab.** `glab` covers merge requests, pipelines, issues and
  releases. This is read-only artifact metadata and always will be.
- **A one-off lookup on a machine where `glab` is already authenticated.** Running the CLI beats
  deploying a service.
- **Anything outside Maven layout** — npm, PyPI, container tags. Out of scope.

The reverse also holds: many MCP clients grant no shell at all, and a CLI-wrapping skill needs the
tool installed, authenticated and configured on every laptop and CI runner that uses it. One
container everyone points at is a different operational shape.

## Backends

Sources are consulted in precedence order; the first one that has the artifact wins. Keyword search
instead queries every backend concurrently and interleaves the results round-robin, so a registry
with many matches cannot fill the whole page and hide the others. De-duplication happens in
precedence order, so a coordinate offered by two registries resolves to the higher-precedence one.

| Backend | Default | Notes |
|---|---|---|
| Maven Central | always on | Also provides keyword search, via Central's Solr endpoint. |
| Gradle Plugin Portal | on | Public, no credentials. Covers plugin marker artifacts. No search API. |
| GitLab Maven registry | off | For internal artifacts. Needs a URL and a token. |

### Configuring the GitLab backend

No registry URL is committed. Set `gitlab.enabled=true` and supply the rest through the environment:

| Variable | Purpose |
|---|---|
| `GITLAB_BASE_URL` | Full GitLab Maven registry URL (see below). |
| `GITLAB_TOKEN` | Read token for a private registry. |
| `GITLAB_SEARCH_URL` | Optional. Only to override the packages-API URL otherwise derived from the base URL. |

The registry level is encoded in the base URL itself, and `<id>` may be numeric or a URL-encoded
path (`my-org%2Fmy-group`):

```
project   https://gitlab.example.com/api/v4/projects/<id>/packages/maven
group     https://gitlab.example.com/api/v4/groups/<id>/-/packages/maven
instance  https://gitlab.example.com/api/v4/packages/maven
```

`gitlab.token-header` selects how the token is sent: `PRIVATE_TOKEN` (default), `DEPLOY_TOKEN`,
`JOB_TOKEN` or `BEARER`.

## Running it

### Docker

```sh
docker run -d --name maven-mcp -p 8080:8080 zeletrik/maven-mcp-server:latest
```

That is the whole setup for the public backends — Maven Central and the Gradle Plugin Portal need no
credentials. MCP clients then connect to `http://localhost:8080/mcp`.

To add your internal GitLab registry, pass the token from the host rather than writing it into a
command that lands in your shell history:

```sh
docker run -d --name maven-mcp -p 8080:8080 \
  -e GITLAB_ENABLED=true \
  -e GITLAB_BASE_URL="https://gitlab.example.com/api/v4/groups/<id>/-/packages/maven" \
  -e GITLAB_TOKEN="$GITLAB_TOKEN" \
  zeletrik/maven-mcp-server:latest
```

### Docker Compose

```yaml
# compose.yaml
services:
  maven-mcp:
    image: zeletrik/maven-mcp-server:latest
    container_name: maven-mcp
    restart: unless-stopped
    ports:
      - "8080:8080"
    environment:
      # Maven Central and the Gradle Plugin Portal are on by default and need nothing here.
      # Everything below is only for an internal GitLab registry.
      GITLAB_ENABLED: "true"
      GITLAB_BASE_URL: "https://gitlab.example.com/api/v4/groups/<id>/-/packages/maven"
      # Read from the host environment or a .env file beside this compose file — never inline.
      GITLAB_TOKEN: "${GITLAB_TOKEN:?set GITLAB_TOKEN in your environment or .env}"
      # Optional: only if the packages API cannot be derived from GITLAB_BASE_URL.
      # GITLAB_SEARCH_URL: "https://gitlab.example.com/api/v4/groups/<id>/packages"
      # Optional: drop the Gradle Plugin Portal and serve Maven Central only.
      # GRADLEPLUGINPORTAL_ENABLED: "false"
```

```sh
docker compose up -d
```

The image already declares its own `HEALTHCHECK` (a GET on `/actuator/health`), so Compose reports
the container healthy once the server answers — no `healthcheck:` block needed here.

Any setting in `application.yaml` can be overridden by an environment variable using Spring Boot's
relaxed binding: uppercase it, turn dots into underscores, and **delete** dashes. That last rule is
easy to get wrong — `gradle-plugin-portal.enabled` becomes `GRADLEPLUGINPORTAL_ENABLED`, not
`GRADLE_PLUGIN_PORTAL_ENABLED`.

### From source

Requires a Java 25 toolchain. Gradle's toolchain auto-detection is disabled, so `JAVA_HOME` must
point at it:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew bootRun   # serves on :8080
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew build     # compile + run the tests
```

### Connecting a client

Transport is HTTP (SSE / streamable-HTTP) at `/mcp`; there is no STDIO transport. No authentication
is applied to the transport, so run it on a trusted network rather than exposing the port publicly.

## Observability

Three Actuator endpoints are exposed, and deliberately no more — the transport has no
authentication, so publishing `env`, `beans` or `configprops` alongside them would hand over the
configuration of whatever registry this is pointed at.

| Endpoint | Purpose |
|---|---|
| `/actuator/health` | `UP`/`DOWN`, with `liveness` and `readiness` groups. The container healthcheck uses this. |
| `/actuator/info` | Build and version information. |
| `/actuator/prometheus` | Metrics in Prometheus text format, for scraping. |

Beyond the usual JVM and `http_server_requests` series, outbound calls to the registries are
instrumented as `http_client_requests`, tagged by `client_name` — so you can see per-backend latency
and error rates, and tell "Maven Central is slow" apart from "this server is slow":

```
http_client_requests_seconds_count{client_name="repo1.maven.org",method="GET",outcome="SUCCESS",...}
```

A scrape config pointed at `maven-mcp:8080` with a metrics path of `/actuator/prometheus` is all
Prometheus needs.

## How failures are reported

Every tool returns a structured result rather than failing the call, so a model can react to the
outcome instead of seeing a broken tool. Each carries a `status`:

| `status` | Meaning |
|---|---|
| `ok` | The payload is populated. |
| `not_found` | The artifact, version or file genuinely does not exist on any backend. |
| `source_error` | A backend was unreachable, timed out, or returned unparseable data. |
| `validation_error` | The coordinates were rejected before any network call; `message` names the parameter. |

The distinction between `not_found` and `source_error` is deliberate: the first is an answer, the
second means the question could not be answered. A non-`ok` result never carries a partial payload,
and `message` holds the detail.

A zero-match search is an `ok` result with an empty list — "nothing matched" is a valid answer.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) — build commands, the design rules the tests enforce, and the
commit conventions that drive releases.

## License

[MIT](LICENSE).
