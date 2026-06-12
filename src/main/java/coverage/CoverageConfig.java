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
 * before (zero-config). A config only ever <i>removes</i> things from the
 * denominator — it never changes default behaviour on its own.
 *
 * <p>Example {@code openapi-coverage.conf}:
 * <pre>
 * # lines are: key = value   (# starts a comment, blank lines ignored)
 * ignore-deprecated = true
 * ignore-status = 500
 * ignore-status = 503
 * ignore-operation = POST /internal/.*
 * ignore-operation = /admin/.*
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
 * </ul>
 */
@Slf4j
public record CoverageConfig(
        List<OperationMatcher> ignoreOperations,
        Set<Integer> ignoreStatuses,
        boolean ignoreDeprecated) {

    private static final Set<String> HTTP_METHODS = Set.of(
        "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE");

    /**
     * Matches a spec operation by HTTP method (optional) and a path regex.
     */
    public record OperationMatcher(String method, String pathPattern) {
    }

    /** @return a no-op config (nothing ignored). */
    public static CoverageConfig empty() {
        return new CoverageConfig(List.of(), new LinkedHashSet<>(), false);
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
        boolean ignoreDeprecated = false;

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
                default -> log.warn("config {}:{} — unknown key '{}', skipping.",
                    path, lineNo, key);
            }
        }

        final CoverageConfig cfg
            = new CoverageConfig(ignoreOps, ignoreStatuses, ignoreDeprecated);
        log.info("Loaded coverage config from {}: ignoreDeprecated={}, "
            + "ignoreStatuses={}, ignoreOperations={}", path,
            ignoreDeprecated, ignoreStatuses, ignoreOps.size());
        return cfg;
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
}
