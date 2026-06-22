# OpenAPI Coverage Report

A small, dependency-light tool that answers one question for an API test suite:

> **Which operations, status codes, parameters and request bodies described in
> my OpenAPI spec did my tests actually exercise — and what did they hit that
> the spec doesn't describe?**

It records what your tests send, compares it against a live or local OpenAPI
3.x / Swagger 2.0 document, and renders a single self-contained, interactive
**HTML report** (no server, no external assets).

![coverage report](docs/screenshot.png)

## Why

OpenAPI coverage needs to work with current specs and produce useful feedback
without a heavy parser/runtime stack. This tool:

- Parses OpenAPI **3.0.x and 3.1.x** (and Swagger 2.0) with plain Jackson — no
  swagger-parser dependency.
- Produces a modern, **interactive** single-file HTML report.
- Records coverage straight from **REST Assured** via a tiny filter (or from any
  source that writes the simple JSON record format described below).

## How it works

```
  ┌─────────────┐   per-request JSON     ┌──────────────────────┐   HTML
  │ Your tests  │ ─────────────────────► │  coverage-output/     │ ─────────►  coverage-report.html
  │ (REST       │  OpenApiCoverageFilter │  *-coverage.json      │  reporter
  │  Assured)   │                        └──────────────────────┘
  └─────────────┘                                   ▲
                                                    │  compared against
                                          ┌──────────────────────┐
                                          │ OpenAPI 3.x spec      │
                                          │ (URL or local file)   │
                                          └──────────────────────┘
```

1. **Record** — `coverage.restassured.OpenApiCoverageFilter` writes one tiny JSON
   record per HTTP request (method, path, params, body-present, status code).
   Sensitive header values (auth tokens, API keys, cookies) are recorded by name
   only, never by value.
2. **Analyze** — `coverage.CoverageComparator` loads the spec, matches each
   recorded request to a spec operation (templated paths and any deployment base
   path are handled automatically), and evaluates coverage *conditions*.
3. **Report** — `coverage.HtmlReportGenerator` renders the interactive HTML.

## Add it to your build

Gradle:

```gradle
dependencies {
    testImplementation 'io.github.dm9tr0:openapi-coverage-report:0.4.0'
}
```

Maven:

```xml
<dependency>
  <groupId>io.github.dm9tr0</groupId>
  <artifactId>openapi-coverage-report</artifactId>
  <version>0.4.0</version>
</dependency>
```

> REST Assured is a `compileOnly` dependency of this library, so it is **not**
> pulled in transitively. The core engine (spec parsing, coverage analysis, HTML
> report) needs only Jackson + SLF4J. The optional
> `coverage.restassured.OpenApiCoverageFilter` requires REST Assured on your test
> classpath — which test projects already have.

> **Logging:** the library logs through SLF4J and ships **no** binding, so it
> never conflicts with your logging setup. If you want to see its logs, make sure
> your project has an SLF4J binding on the classpath — most do (e.g. Logback). For
> a bare project, the simplest option is `org.slf4j:slf4j-simple`.

## Usage

### 1. Record coverage in your tests

Register the filter on your REST Assured requests, pointing it at an output
directory:

```java
import coverage.restassured.OpenApiCoverageFilter;
import io.restassured.RestAssured;
import java.nio.file.Path;

RestAssured.filters(new OpenApiCoverageFilter(Path.of("build/coverage-output")));
```

Run your test suite as usual — one `*-coverage.json` file is written per request.

### 2. Generate the report in your project

Build the local CLI distribution:

```bash
./gradlew installDist
```

Run the generated executable:

```bash
build/install/openapi-coverage/bin/openapi-coverage \
  --spec examples/openapi.json \
  --input examples/coverage-output \
  --output examples/report
```

Add `--config <path>` when you have a config file, and `--min-coverage <N>` in
CI when the threshold matches your expected recorded coverage.

The positional form is still supported:

```bash
build/install/openapi-coverage/bin/openapi-coverage \
  examples/openapi.json examples/openapi.json examples/coverage-output examples/report
```

For development inside this repository, the same main class can also be run
without installing the distribution:

```bash
./gradlew run --args="--spec examples/openapi.json --input examples/coverage-output --output examples/report"
```

The report generator is a `main` class (`coverage.OpenApiCoverageReporter`) —
there is no Gradle plugin, so wire a `JavaExec` task into your own
`build.gradle`:

```gradle
// SLF4J binding for the reporter's console logs only — kept off the
// published/runtime classpath so it never clashes with your logging setup.
configurations { coverageLogging }
dependencies { coverageLogging "org.slf4j:slf4j-simple:2.0.18" }

tasks.register("coverageReport", JavaExec) {
    classpath = sourceSets.test.runtimeClasspath + configurations.coverageLogging
    mainClass = "coverage.OpenApiCoverageReporter"
    args(
        "https://example.test/openapi.json",  // spec URL or local file
        "",                                      // fallback spec path ("" = none)
        "build/coverage-output",                 // recorded *-coverage.json dir
        "build/reports",                         // output dir
        // optional flags:
        "--min-coverage", "70",                  // exit 2 if coverage < 70%
        "--config", "openapi-coverage.conf")     // ignore/filter rules
}
```

Run it after your tests:

```bash
./gradlew test coverageReport
```

Arguments — positional first, then optional flags (anywhere):

| Arg | Meaning |
|-----|---------|
| `specUrlOrFile` | OpenAPI document — an `http(s)` URL **or** a local file path |
| `fallbackSpecPath` | Local spec used if the first can't be loaded; optional in named CLI mode |
| `coverageOutputDir` | Directory of recorded `*-coverage.json` files |
| `outputDir` *(optional)* | Where reports are written (default `build/reports`) |
| `--fallback <path>` *(optional)* | Local fallback spec for named CLI mode |
| `--min-coverage <N>` *(optional)* | Exit code **2** if coverage % is below N (CI gate) |
| `--config <path>` *(optional)* | Path to a config file (see below) |

Outputs written to `<outputDir>` unless renamed in config:

- `coverage-report.html` — the interactive report
- `coverage-report.json` — machine-readable result for CI (metrics, gating)

### 3. Optional config (`openapi-coverage.conf`)

A flat `key = value` file (no JSON). It only ever *removes* things from the
coverage denominator unless explicitly noted — with no config the tool runs with
sensible defaults:

```
# one setting per line; '#' starts a comment, blank lines are ignored
ignore-deprecated = true
ignore-status = 500
ignore-status = 503
ignore-operation = POST /internal/.*
ignore-operation = /admin/.*
only-declared-status = true
html-report-name = api-coverage.html
json-report-name = api-coverage.json
suggest-test-gaps = true
suggest-status = 401
suggest-status = 403
suggest-empty-parameter = true
suggest-blank-parameter = true
suggest-missing-required-parameter = true
suggest-invalid-media-type = true
```

| Key | Default | Repeatable | Effect |
|-----|---------|------------|--------|
| `ignore-deprecated` | `false` | no | Removes deprecated operations from all coverage calculations. |
| `ignore-status` | none | yes | Removes a documented status from response-status conditions. |
| `ignore-operation` | none | yes | Removes operations matching `[METHOD] <path-regex>`; method is optional. |
| `only-declared-status` | `false` | no | Adds a condition that fails when recorded calls return statuses not declared in the spec. |
| `html-report-name` | `coverage-report.html` | no | Changes the generated HTML filename. |
| `json-report-name` | `coverage-report.json` | no | Changes the generated JSON filename. |
| `suggest-test-gaps` | `false` | no | Enables advisory suggestions; suggestions never affect coverage percentage. |
| `suggest-status` | `400`, `401`, `403`, `404` | yes | Statuses to suggest as missing negative/status tests when `suggest-test-gaps` is enabled. |
| `suggest-empty-parameter` | `true` | no | Suggests an empty-value parameter test after a non-empty value was observed. |
| `suggest-blank-parameter` | `true` | no | Suggests a blank-value parameter test after a non-empty value was observed. |
| `suggest-missing-required-parameter` | `true` | no | Suggests omitting a required parameter after it was observed in recorded calls. |
| `suggest-invalid-media-type` | `true` | no | Suggests an unsupported `Content-Type` test for operations with a request body. |

### 4. Compatibility matrix

| Capability | Support |
|------------|---------|
| Swagger 2.0 | Yes |
| OpenAPI 3.0.x | Yes |
| OpenAPI 3.1.x | Yes |
| Local `$ref` for parameters/request bodies/responses | Yes |
| Path-level parameters | Yes |
| REST Assured recorder | Yes |
| Generic JSON record input | Yes |
| CLI run from terminal | Yes, via `./gradlew installDist` then `build/install/openapi-coverage/bin/openapi-coverage ...` |
| HTML report | Yes |
| JSON report | Yes |
| Custom report filenames | Yes, via flat config |
| Deprecated-operation exclusion | Yes |
| Status/operation ignore rules | Yes |
| Undeclared status reporting | Yes |
| `only-declared-status` as a counted condition | Optional |
| Advisory negative-test suggestions | Optional |

The compatibility fixture suite lives under `src/test/resources/fixtures/` and is
covered by unit tests. Add new fixture specs there when extending Swagger/OpenAPI
parsing behavior.

## What the report shows

- **Overall conditions coverage** with an at-a-glance banner and donut chart.
- **Per-operation** coverage: status codes, parameters (defined vs sent), and a
  request-body check — each condition coloured green (covered) / red (not), with
  details (e.g. `Undeclared status: 400`).
- **Coverage by tag** — per-tag rollups taken from the spec's operation tags.
- **Undeclared statuses** — a separate, opt-in signal for operations that returned
  a status code the spec does not describe. These are **not** counted against
  coverage by default (the spec is the gap, not the test), but are surfaced via a
  badge, a filter, and a summary count so they're easy to find. Enable
  `only-declared-status = true` to count them as uncovered conditions.
- **Suggested test gaps** — optional advisory hints for common negative/status
  cases. These never affect the coverage percentage.
- **Search, filters, deep-links** — filter by coverage/tag/missing-condition,
  search by method/path/tag/summary, and share a URL hash that restores the view.
- **Worst-first ordering**, expand/collapse, sortable columns.
- **Automatic light/dark theme** following the browser, with a manual toggle.
- **Generation info** — spec source, requests parsed/matched, operation count,
  deprecated count, and a timestamp.

## Coverage calculation

For every operation in the spec the reporter evaluates *conditions*:

- **Response Status** — one per documented status code; covered when that status
  was actually received.
- **Check parameter value** — each documented query/header parameter; covered when
  sent at least once.
- **Request Body Availability** — for operations whose spec declares a request
  body; covered when a non-empty body was sent.

Coverage percentage = covered conditions / total conditions. Operations are
classified **Full / Partial / Empty / No call**.

> **Undeclared statuses are deliberately excluded from the percentage.** An
> operation whose documented behaviour is fully exercised should not be penalised
> for also returning an undocumented status — that's a spec gap, surfaced
> separately, not a test gap.

## Path matching

Recorded request paths are matched to spec operations with templated-path regex
(`/resources/123` matches `/resources/{id}`). Any deployment **base path** is absorbed
automatically: the matcher tries the full recorded path, then progressively strips
leading segments until it matches a spec path — so `/api/resources/1` matches a spec
that declares `/resources/{id}` with no configuration.

## The coverage record format

Each file under the coverage output directory is a minimal JSON document
(`"format":"openapi-coverage-v1"`). You can produce it from any HTTP layer, not
just REST Assured:

```json
{
  "format": "openapi-coverage-v1",
  "paths": {
    "/resources": {
      "post": {
        "parameters": [{ "name": "Accept", "in": "header", "value": "*/*" }],
        "requestBody": { "present": true, "contentType": "application/json" },
        "mediaTypes": ["application/json"],
        "responses": { "201": {} }
      }
    }
  }
}
```

## Try the bundled example

A tiny spec and a handful of recorded requests live under `examples/`:

```bash
./gradlew coverageReport -PreportArgs="\
  examples/openapi.json examples/openapi.json \
  examples/coverage-output examples/report"
# open examples/report/coverage-report.html
```

It produces the report shown in the screenshot above (a mix of Full / Partial /
No-call operations, an undeclared status, and a deprecated operation).

## Building and testing

```bash
./gradlew assemble        # compile the engine + filter
./gradlew installDist     # build the local openapi-coverage CLI
./gradlew test            # run the unit tests
./gradlew coverageReport  # generate a report (see Usage)
```

Requires a JDK 21+. The engine depends only on Jackson and SLF4J; the optional
REST Assured filter needs REST Assured on your test classpath. Tests use JUnit 5
and AssertJ.

## License

[MIT](LICENSE).
