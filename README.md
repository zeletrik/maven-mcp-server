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

## Backends

Sources are consulted in precedence order; the first one that has the artifact wins. Keyword search
instead queries every backend and merges the results, de-duplicated by coordinates.

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

Requires a Java 25 toolchain. Gradle's toolchain auto-detection is disabled, so `JAVA_HOME` must
point at it:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew bootRun   # serves on :8080
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew build     # compile + run the tests
```

Transport is HTTP (SSE / streamable-HTTP); there is no STDIO transport. No authentication is applied
to the transport, so run it on a trusted network.

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

## License

[MIT](LICENSE).
