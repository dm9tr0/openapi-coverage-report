package coverage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenApiCoverageReporterTest {

    private static final String SPEC =
        "{ \"openapi\": \"3.0.3\", \"paths\": {} }";

    @Test
    void loadsSpecFromLocalFilePrimarySource(@TempDir final Path dir)
            throws Exception {
        final Path spec = dir.resolve("openapi.json");
        Files.writeString(spec, SPEC);
        final OpenApiCoverageReporter reporter = new OpenApiCoverageReporter(
            spec.toString(), "missing-fallback.json",
            dir.toString(), dir.toString());
        assertThat(reporter.loadSpec()).contains("\"openapi\"");
    }

    @Test
    void fallsBackToFallbackSpecWhenPrimaryMissing(@TempDir final Path dir)
            throws Exception {
        final Path fallback = dir.resolve("fallback.json");
        Files.writeString(fallback, SPEC);
        final OpenApiCoverageReporter reporter = new OpenApiCoverageReporter(
            dir.resolve("does-not-exist.json").toString(),
            fallback.toString(), dir.toString(), dir.toString());
        assertThat(reporter.loadSpec()).contains("\"openapi\"");
    }

    @Test
    void loadsSpecWithoutFallbackWhenPrimarySourceExists(@TempDir final Path dir)
            throws Exception {
        final Path spec = dir.resolve("openapi.json");
        Files.writeString(spec, SPEC);
        final OpenApiCoverageReporter reporter = new OpenApiCoverageReporter(
            spec.toString(), null, dir.toString(), dir.toString());
        assertThat(reporter.loadSpec()).contains("\"openapi\"");
    }

    @Test
    void returnsNullWhenBothSourcesFail(@TempDir final Path dir) {
        final OpenApiCoverageReporter reporter = new OpenApiCoverageReporter(
            dir.resolve("nope.json").toString(),
            dir.resolve("also-nope.json").toString(),
            dir.toString(), dir.toString());
        assertThat(reporter.loadSpec()).isNull();
    }

    @Test
    void runUsesConfiguredReportFileNames(@TempDir final Path dir)
            throws Exception {
        final Path spec = dir.resolve("openapi.json");
        Files.writeString(spec, """
            { "openapi": "3.1.0", "paths": {
              "/resources": { "get": { "responses": { "200": {} } } } } }
            """);
        final Path coverageDir = dir.resolve("coverage-output");
        Files.createDirectories(coverageDir);
        Files.writeString(coverageDir.resolve("one-coverage.json"), """
            { "format": "openapi-coverage-v1", "paths": {
              "/resources": { "get": { "responses": { "200": {} } } } } }
            """);
        final Path config = dir.resolve("openapi-coverage.conf");
        Files.writeString(config, """
            html-report-name = api-coverage.html
            json-report-name = api-coverage.json
            """);
        final Path out = dir.resolve("reports");

        final OpenApiCoverageReporter reporter = new OpenApiCoverageReporter(
            spec.toString(), spec.toString(), coverageDir.toString(),
            out.toString(), -1, config.toString());

        final PrintStream originalOut = System.out;
        final String output;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                PrintStream replacement = new PrintStream(bytes, true,
                    StandardCharsets.UTF_8)) {
            System.setOut(replacement);
            reporter.run();
            output = bytes.toString(StandardCharsets.UTF_8);
        } finally {
            System.setOut(originalOut);
        }

        assertThat(out.resolve("api-coverage.html")).exists();
        assertThat(out.resolve("api-coverage.json")).exists();
        assertThat(output)
            .contains("Coverage: 1/1 conditions covered (100.0%)")
            .contains(out.resolve("api-coverage.html")
                .toAbsolutePath().toString())
            .contains(out.resolve("api-coverage.json")
                .toAbsolutePath().toString());
    }
}
