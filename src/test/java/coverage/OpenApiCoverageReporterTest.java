package coverage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
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
    void returnsNullWhenBothSourcesFail(@TempDir final Path dir) {
        final OpenApiCoverageReporter reporter = new OpenApiCoverageReporter(
            dir.resolve("nope.json").toString(),
            dir.resolve("also-nope.json").toString(),
            dir.toString(), dir.toString());
        assertThat(reporter.loadSpec()).isNull();
    }
}
