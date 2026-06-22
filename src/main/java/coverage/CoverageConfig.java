package coverage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Optional coverage configuration in a flat, human-friendly {@code key = value}
 * format (ghostty-style) — one setting per line, no JSON, no extra dependency.
 *
 * <p><b>Optional by design:</b> with no config the reporter behaves exactly as
 * before (zero-config). Ignore keys remove things from the denominator,
 * {@code only-declared-status} adds an opt-in counted condition, and
 * suggestion keys emit advisory planning signals without changing coverage.
 *
 * <p>Example {@code openapi-coverage.conf}:
 * <pre>
 * # lines are: key = value   (# starts a comment, blank lines ignored)
 * ignore-deprecated = true
 * ignore-status = 500
 * ignore-status = 503
 * ignore-operation = POST /internal/.*
 * ignore-operation = /admin/.*
 * only-declared-status = true
 * suggest-test-gaps = true
 * </pre>
 *
 * Keys:
 * <ul>
 *   <li>{@code ignore-deprecated} — {@code true}/{@code false}; drop deprecated
 *       operations from coverage.</li>
 *   <li>{@code ignore-status} — a status code; repeat the key for several. The
 *       status no longer counts as a condition.</li>
 *   <li>{@code ignore-operation} — {@code [METHOD] <path-regex>}; drop matching
 *       operations. Method is optional (any method if omitted).</li>
 *   <li>{@code only-declared-status} — fail a condition when runtime returns an
 *       HTTP status not declared by the spec.</li>
 *   <li>{@code html-report-name}/{@code json-report-name} — override generated
 *       report filenames.</li>
 *   <li>{@code suggest-test-gaps} and {@code suggest-*} keys — emit advisory
 *       missing-test suggestions without changing coverage percentages.</li>
 * </ul>
 */
@Slf4j
public record CoverageConfig(
        List<OperationMatcher> ignoreOperations,
        Set<Integer> ignoreStatuses,
        boolean ignoreDeprecated,
        boolean onlyDeclaredStatus,
        String htmlReportName,
        String jsonReportName,
        boolean suggestTestGaps,
        Set<Integer> suggestStatuses,
        boolean suggestEmptyParameter,
        boolean suggestBlankParameter,
        boolean suggestMissingRequiredParameter,
        boolean suggestInvalidMediaType) {

    private static final Set<String> HTTP_METHODS = Set.of(
        "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE");
    private static final String DEFAULT_HTML_REPORT_NAME = "coverage-report.html";
    private static final String DEFAULT_JSON_REPORT_NAME = "coverage-report.json";
    private static final Set<Integer> DEFAULT_SUGGEST_STATUSES
        = Set.of(400, 401, 403, 404);

    public CoverageConfig {
        ignoreOperations = List.copyOf(ignoreOperations);
        ignoreStatuses = Set.copyOf(ignoreStatuses);
        htmlReportName = defaultIfBlank(htmlReportName, DEFAULT_HTML_REPORT_NAME);
        jsonReportName = defaultIfBlank(jsonReportName, DEFAULT_JSON_REPORT_NAME);
        suggestStatuses = Set.copyOf(suggestStatuses);
    }

    /**
     * Matches a spec operation by HTTP method (optional) and a path regex.
     */
    public record OperationMatcher(String method, String pathPattern) {
    }

    /** @return a no-op config (nothing ignored). */
    public static CoverageConfig empty() {
        return new CoverageConfig(
            List.of(), new LinkedHashSet<>(), false, false,
            DEFAULT_HTML_REPORT_NAME, DEFAULT_JSON_REPORT_NAME, false,
            new LinkedHashSet<>(), true, true, true, true);
    }

    /**
     * Loads config from a flat {@code key = value} file. A null/blank path or
     * any read error yields {@link #empty()} (the tool must always run with
     * sensible defaults).
     *
     * @param path path to the config file, or null/blank for none
     * @return the loaded config, or an empty config
     */
    public static CoverageConfig load(final String path) {
        if (path == null || path.isBlank()) {
            return empty();
        }
        final List<OperationMatcher> ignoreOps = new ArrayList<>();
        final Set<Integer> ignoreStatuses = new LinkedHashSet<>();
        final Set<Integer> suggestStatuses = new LinkedHashSet<>();
        boolean ignoreDeprecated = false;
        boolean onlyDeclaredStatus = false;
        String htmlReportName = DEFAULT_HTML_REPORT_NAME;
        String jsonReportName = DEFAULT_JSON_REPORT_NAME;
        boolean suggestTestGaps = false;
        boolean suggestEmptyParameter = true;
        boolean suggestBlankParameter = true;
        boolean suggestMissingRequiredParameter = true;
        boolean suggestInvalidMediaType = true;

        final List<String> lines;
        try {
            lines = Files.readAllLines(Path.of(path));
        } catch (final IOException e) {
            log.warn("Failed to read config {} ({}); using defaults.",
                path, e.getMessage());
            return empty();
        }

        int lineNo = 0;
        for (final String raw : lines) {
            lineNo++;
            final String line = stripComment(raw).trim();
            if (line.isEmpty()) {
                continue;
            }
            final int eq = line.indexOf('=');
            if (eq < 0) {
                log.warn("config {}:{} — no '=', skipping: {}",
                    path, lineNo, line);
                continue;
            }
            final String key = line.substring(0, eq).trim();
            final String value = line.substring(eq + 1).trim();
            switch (key) {
                case "ignore-deprecated" ->
                    ignoreDeprecated = Boolean.parseBoolean(value);
                case "ignore-status" -> parseStatus(value, ignoreStatuses, path, lineNo);
                case "ignore-operation" -> ignoreOps.add(parseOperation(value));
                case "only-declared-status" ->
                    onlyDeclaredStatus = Boolean.parseBoolean(value);
                case "html-report-name" ->
                    htmlReportName = defaultIfBlank(value, DEFAULT_HTML_REPORT_NAME);
                case "json-report-name" ->
                    jsonReportName = defaultIfBlank(value, DEFAULT_JSON_REPORT_NAME);
                case "suggest-test-gaps" ->
                    suggestTestGaps = Boolean.parseBoolean(value);
                case "suggest-status" ->
                    parseStatus(value, suggestStatuses, path, lineNo);
                case "suggest-empty-parameter" ->
                    suggestEmptyParameter = Boolean.parseBoolean(value);
                case "suggest-blank-parameter" ->
                    suggestBlankParameter = Boolean.parseBoolean(value);
                case "suggest-missing-required-parameter" ->
                    suggestMissingRequiredParameter = Boolean.parseBoolean(value);
                case "suggest-invalid-media-type" ->
                    suggestInvalidMediaType = Boolean.parseBoolean(value);
                default -> log.warn("config {}:{} — unknown key '{}', skipping.",
                    path, lineNo, key);
            }
        }

        final CoverageConfig cfg
            = new CoverageConfig(
                ignoreOps, ignoreStatuses, ignoreDeprecated,
                onlyDeclaredStatus, htmlReportName, jsonReportName,
                suggestTestGaps, suggestStatuses, suggestEmptyParameter,
                suggestBlankParameter, suggestMissingRequiredParameter,
                suggestInvalidMediaType);
        log.info("Loaded coverage config from {}: ignoreDeprecated={}, "
            + "ignoreStatuses={}, ignoreOperations={}, onlyDeclaredStatus={}, "
            + "suggestTestGaps={}", path, ignoreDeprecated, ignoreStatuses,
            ignoreOps.size(), onlyDeclaredStatus, suggestTestGaps);
        return cfg;
    }

    private static String defaultIfBlank(final String value,
            final String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String stripComment(final String line) {
        final int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    private static void parseStatus(final String value, final Set<Integer> into,
            final String path, final int lineNo) {
        try {
            into.add(Integer.parseInt(value.trim()));
        } catch (final NumberFormatException e) {
            log.warn("config {}:{} — ignore-status not a number: {}",
                path, lineNo, value);
        }
    }

    private static OperationMatcher parseOperation(final String value) {
        final String[] parts = value.split("\\s+", 2);
        if (parts.length == 2 && HTTP_METHODS.contains(parts[0].toUpperCase())) {
            return new OperationMatcher(parts[0].toUpperCase(), parts[1].trim());
        }
        return new OperationMatcher(null, value.trim());
    }

    /**
     * @param op a spec operation
     * @return true if the operation should be excluded from coverage entirely
     */
    public boolean ignores(final OpenApiSpecParser.ApiOperation op) {
        if (ignoreDeprecated && op.deprecated()) {
            return true;
        }
        for (final OperationMatcher m : ignoreOperations) {
            final boolean methodOk = m.method() == null
                || m.method().equalsIgnoreCase(op.method());
            if (methodOk && m.pathPattern() != null
                    && op.path().matches(m.pathPattern())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param statusCode a defined status code
     * @return true if this status should not count as a coverage condition
     */
    public boolean ignoresStatus(final int statusCode) {
        return ignoreStatuses.contains(statusCode);
    }

    /**
     * @return explicitly configured suggestion statuses, or common API negative
     *         statuses when no custom values are configured
     */
    public Set<Integer> effectiveSuggestStatuses() {
        return suggestStatuses.isEmpty()
            ? DEFAULT_SUGGEST_STATUSES : suggestStatuses;
    }
}
