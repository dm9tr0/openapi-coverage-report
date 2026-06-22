package coverage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Main orchestrator for OpenAPI coverage report generation.
 *
 * <p>Workflow:
 * <ol>
 *   <li>Load the OpenAPI spec from a URL or local file (with fallback)</li>
 *   <li>Parse spec using {@link OpenApiSpecParser}</li>
 *   <li>Read per-request coverage data from the coverage output directory</li>
 *   <li>Compare recorded operations against spec using
 *       {@link CoverageComparator}</li>
 *   <li>Generate HTML report using {@link HtmlReportGenerator}</li>
 * </ol>
 */
@Slf4j
public final class OpenApiCoverageReporter {

    /**
     * Entry point for the report generator when invoked via JavaExec.
     *
     * <p>Named: {@code --spec <specUrlOrFile> --input <coverageOutputDir>}.
     * Optional flags (anywhere): {@code --fallback <path>},
     * {@code --output <dir>}, {@code --min-coverage <N>} (exit code 2 if
     * coverage % is below N), {@code --config <path>} (a flat
     * {@code key = value} config, see {@link CoverageConfig}). The legacy
     * positional form remains supported:
     * {@code <specUrl> <fallbackSpecPath> <coverageOutputDir> [outputDir]}.
     *
     * @param args positional arguments plus optional flags
     */
    public static void main(final String[] args) {
        if (List.of(args).contains("--help")) {
            printUsage();
            return;
        }
        final List<String> positional = new ArrayList<>();
        String specUrl = null;
        String fallbackSpecPath = null;
        String coverageOutputDir = null;
        String outputDir = null;
        double minCoverage = -1;
        String configPath = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--spec" -> specUrl = requireValue(args, ++i, "--spec");
                case "--fallback" ->
                    fallbackSpecPath = requireValue(args, ++i, "--fallback");
                case "--input", "--coverage-dir" ->
                    coverageOutputDir = requireValue(args, ++i, args[i - 1]);
                case "--output", "--output-dir" ->
                    outputDir = requireValue(args, ++i, args[i - 1]);
                case "--min-coverage" ->
                    minCoverage = parseMinCoverage(args, ++i);
                case "--config" -> configPath = requireValue(args, ++i, "--config");
                default -> positional.add(args[i]);
            }
        }
        if (specUrl == null && positional.size() >= 1) {
            specUrl = positional.get(0);
        }
        if (fallbackSpecPath == null && positional.size() >= 2) {
            fallbackSpecPath = positional.get(1);
        }
        if (coverageOutputDir == null && positional.size() >= 3) {
            coverageOutputDir = positional.get(2);
        }
        if (outputDir == null) {
            outputDir = positional.size() > 3 ? positional.get(3) : "build/reports";
        }
        if (specUrl == null || coverageOutputDir == null) {
            printUsage();
            System.exit(1);
        }
        final boolean ok = new OpenApiCoverageReporter(
            specUrl, fallbackSpecPath == null ? "" : fallbackSpecPath,
            coverageOutputDir,
            outputDir, minCoverage, configPath
        ).run();
        if (!ok) {
            System.exit(2);
        }
    }

    private static double parseMinCoverage(final String[] args, final int idx) {
        if (idx >= args.length) {
            System.err.println("Missing value for --min-coverage");
            System.exit(1);
            return -1;
        }
        try {
            return Double.parseDouble(args[idx]);
        } catch (final NumberFormatException e) {
            System.err.println("Invalid --min-coverage value: " + args[idx]);
            System.exit(1);
            return -1;
        }
    }

    private static String requireValue(final String[] args, final int idx,
            final String flag) {
        if (idx >= args.length || args[idx].startsWith("--")) {
            System.err.println("Missing value for " + flag);
            System.exit(1);
            return "";
        }
        return args[idx];
    }

    private static void printUsage() {
        System.err.println("Usage: openapi-coverage "
            + "--spec <specUrlOrFile> --input <coverageOutputDir> "
            + "[--fallback <fallbackSpecPath>] [--output <outputDir>] "
            + "[--min-coverage <N>] [--config <path>]");
        System.err.println("   or: openapi-coverage <specUrlOrFile> "
            + "<fallbackSpecPath> <coverageOutputDir> [outputDir] "
            + "[--min-coverage <N>] [--config <path>]");
    }

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final String specUrl;
    private final String fallbackSpecPath;
    private final String coverageOutputDir;
    private final String outputDir;
    private final double minCoverage;
    private final String configPath;

    /**
     * Creates a reporter with no coverage threshold and no config (defaults).
     *
     * @param specUrl          URL to download the live OpenAPI spec
     * @param fallbackSpecPath path to static spec file for fallback
     * @param coverageOutputDir directory with per-request coverage JSON files
     * @param outputDir        directory for the generated report
     */
    public OpenApiCoverageReporter(
            final String specUrl,
            final String fallbackSpecPath,
            final String coverageOutputDir,
            final String outputDir) {
        this(specUrl, fallbackSpecPath, coverageOutputDir, outputDir, -1, null);
    }

    /**
     * Creates a new coverage reporter.
     *
     * @param specUrl          URL to download the live OpenAPI spec
     * @param fallbackSpecPath path to static spec file for fallback
     * @param coverageOutputDir directory with per-request coverage JSON files
     * @param outputDir        directory for the generated report
     * @param minCoverage      minimum coverage %; below it {@link #run()}
     *                         returns false (negative = no threshold)
     * @param configPath       path to an optional flat config file, or null
     */
    public OpenApiCoverageReporter(
            final String specUrl,
            final String fallbackSpecPath,
            final String coverageOutputDir,
            final String outputDir,
            final double minCoverage,
            final String configPath) {
        this.specUrl = specUrl;
        this.fallbackSpecPath = fallbackSpecPath;
        this.coverageOutputDir = coverageOutputDir;
        this.outputDir = outputDir;
        this.minCoverage = minCoverage;
        this.configPath = configPath;
    }

    /**
     * Runs the full coverage report generation pipeline.
     *
     * @return false if the spec could not be loaded, or if a {@code minCoverage}
     *         threshold was set and coverage fell below it; true otherwise
     */
    public boolean run() {
        log.info("=== OpenAPI Coverage Report ===");
        log.info("Spec URL: {}", specUrl);
        if (fallbackSpecPath != null && !fallbackSpecPath.isBlank()) {
            log.info("Fallback: {}", fallbackSpecPath);
        }
        log.info("Coverage: {}", coverageOutputDir);
        log.info("Output:   {}", outputDir);

        // Step 1: Load spec (live download with fallback)
        final String specJson = loadSpec();
        if (specJson == null) {
            log.error("No spec available (live download failed and no fallback). "
                + "Cannot generate coverage report.");
            return false;
        }

        // Step 2: Parse spec
        final OpenApiSpecParser parser = new OpenApiSpecParser(specJson);
        final OpenApiSpecParser.ApiSpec parsedSpec = parser.spec();
        log.info("Parsed OpenAPI spec: version={}, operations={}",
            parsedSpec.version(), parsedSpec.operations().size());

        // Step 3: Read coverage files
        final List<CoverageComparator.RecordedOperation> recordedOps
            = CoverageComparator.readCoverageFiles(coverageOutputDir);
        if (recordedOps.isEmpty()) {
            log.warn("No recorded operations found. "
                + "Report will show 0% coverage.");
        }

        // Step 4: Analyze coverage (optional config narrows what counts)
        final CoverageConfig config = CoverageConfig.load(configPath);
        final CoverageComparator comparator
            = new CoverageComparator(parsedSpec, recordedOps, config);
        final CoverageComparator.DetailedCoverageResult result
            = comparator.analyze();
        log.info("Coverage: {}/{} conditions covered ({}%)",
            result.coveredConditions(), result.totalConditions(),
            String.format("%.1f", result.totalCoveredPercent()));

        // Step 5: Generate HTML + JSON reports
        final String specSource = deriveSpecSource();
        new HtmlReportGenerator(
            result, parsedSpec.version(), specSource, recordedOps.size(),
            config.htmlReportName())
            .generate(outputDir);
        new JsonReportGenerator(
            result, parsedSpec.version(), specSource, recordedOps.size(),
            config.jsonReportName())
            .generate(outputDir);

        log.info("=== Coverage Report Complete ===");
        printRunSummary(result, config);

        // Step 6: Enforce optional coverage threshold (CI gate)
        if (minCoverage >= 0 && result.totalCoveredPercent() < minCoverage) {
            log.error("Coverage {}% is below the required minimum {}%.",
                String.format("%.1f", result.totalCoveredPercent()),
                String.format("%.1f", minCoverage));
            return false;
        }
        return true;
    }

    private void printRunSummary(final CoverageComparator.DetailedCoverageResult result,
            final CoverageConfig config) {
        final String percent = String.format("%.1f", result.totalCoveredPercent());
        final StringBuilder summary = new StringBuilder()
            .append("Coverage: ").append(result.coveredConditions())
            .append("/").append(result.totalConditions())
            .append(" conditions covered (").append(percent).append("%)")
            .append(System.lineSeparator())
            .append("HTML report: ")
            .append(Path.of(outputDir, config.htmlReportName()).toAbsolutePath())
            .append(System.lineSeparator())
            .append("JSON report: ")
            .append(Path.of(outputDir, config.jsonReportName()).toAbsolutePath())
            .append(System.lineSeparator());
        if (result.suggestions().size() > 0) {
            summary.append("Suggested test gaps: ")
                .append(result.suggestions().size())
                .append(System.lineSeparator());
        }
        System.out.print(summary);
    }

    /**
     * Loads the spec from the primary source (an http(s) URL or a local file
     * path), falling back to the local fallback spec if that fails.
     *
     * @return the spec content, or null if both sources fail
     */
    String loadSpec() {
        final String primary = loadFrom(specUrl);
        if (primary != null) {
            log.info("Using spec from {}", specUrl);
            return primary;
        }

        if (fallbackSpecPath == null || fallbackSpecPath.isBlank()) {
            log.error("Could not load spec from {} and no fallback was set.",
                specUrl);
            return null;
        }
        log.warn("Could not load spec from {}, falling back to: {}",
            specUrl, fallbackSpecPath);
        final String fallback = readFile(fallbackSpecPath);
        if (fallback != null) {
            log.info("Loaded fallback spec from {}", fallbackSpecPath);
        } else {
            log.error("Failed to read fallback spec from {}", fallbackSpecPath);
        }
        return fallback;
    }

    /**
     * Loads spec content from a source that is either an http(s) URL or a
     * local file path (an optional {@code file://} prefix is accepted).
     *
     * @param source the URL or file path
     * @return the spec content, or null if it could not be loaded
     */
    private static String loadFrom(final String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        if (source.startsWith("http://") || source.startsWith("https://")) {
            return downloadSpec(source);
        }
        final String path = source.startsWith("file://")
            ? source.substring("file://".length()) : source;
        return readFile(path);
    }

    private static String readFile(final String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (final IOException e) {
            return null;
        }
    }

    static String downloadSpec(final String url) {
        try {
            final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET()
                .build();

            final HttpResponse<String> response = HTTP_CLIENT.send(
                request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return response.body();
            }
            log.warn("Spec download returned HTTP {} from {}",
                response.statusCode(), url);
            return null;
        } catch (final IOException | InterruptedException e) {
            log.warn("Failed to download spec from {}: {}",
                url, e.getMessage());
            return null;
        }
    }

    private String deriveSpecSource() {
        if (specUrl != null && !specUrl.isBlank()) {
            return specUrl;
        }
        return "Static fallback: " + fallbackSpecPath;
    }
}
