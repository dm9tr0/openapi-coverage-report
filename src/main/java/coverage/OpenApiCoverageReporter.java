package coverage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
     * @param args specUrl fallbackSpecPath coverageOutputDir [outputDir]
     */
    public static void main(final String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: OpenApiCoverageReporter <specUrl>"
                + " <fallbackSpecPath> <coverageOutputDir> [outputDir]");
            System.exit(1);
        }
        final String specUrl = args[0];
        final String fallbackSpecPath = args[1];
        final String coverageOutputDir = args[2];
        final String outputDir = args.length > 3 ? args[3] : "build/reports";
        new OpenApiCoverageReporter(
            specUrl, fallbackSpecPath, coverageOutputDir, outputDir
        ).run();
    }

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final String specUrl;
    private final String fallbackSpecPath;
    private final String coverageOutputDir;
    private final String outputDir;

    /**
     * Creates a new coverage reporter.
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
        this.specUrl = specUrl;
        this.fallbackSpecPath = fallbackSpecPath;
        this.coverageOutputDir = coverageOutputDir;
        this.outputDir = outputDir;
    }

    /**
     * Runs the full coverage report generation pipeline.
     */
    public void run() {
        log.info("=== OpenAPI Coverage Report ===");
        log.info("Spec URL: {}", specUrl);
        log.info("Fallback: {}", fallbackSpecPath);
        log.info("Coverage: {}", coverageOutputDir);
        log.info("Output:   {}", outputDir);

        // Step 1: Load spec (live download with fallback)
        final String specJson = loadSpec();
        if (specJson == null) {
            log.error("No spec available (live download failed and no fallback). "
                + "Cannot generate coverage report.");
            return;
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

        // Step 4: Analyze coverage
        final CoverageComparator comparator
            = new CoverageComparator(parsedSpec, recordedOps);
        final CoverageComparator.DetailedCoverageResult result
            = comparator.analyze();
        log.info("Coverage: {}/{} conditions covered ({}%)",
            result.coveredConditions(), result.totalConditions(),
            String.format("%.1f", result.totalCoveredPercent()));

        // Step 5: Generate HTML report
        final String specSource = deriveSpecSource();
        final HtmlReportGenerator generator = new HtmlReportGenerator(
            result, parsedSpec.version(), specSource, recordedOps.size());
        generator.generate(outputDir);

        log.info("=== Coverage Report Complete ===");
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
