package coverage;

import static org.assertj.core.api.Assertions.assertThat;

import coverage.CoverageComparator.DetailedCoverageResult;
import coverage.OpenApiSpecParser.ApiOperation;
import coverage.OpenApiSpecParser.ApiSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HtmlReportGeneratorTest {

    private DetailedCoverageResult sampleResult() {
        final ApiSpec spec = new ApiSpec("3.0.3", List.of(
            new ApiOperation("/resources", "GET", Set.of(200, 400),
                List.of(), "Resources", "List resources", false, Set.of(), false),
            new ApiOperation("/uncovered", "GET", Set.of(204),
                List.of(), "Resources", "Called but uncovered", false,
                Set.of(), false),
            new ApiOperation("/resources/{id}", "DELETE", Set.of(204),
                List.of(), "Resources", "", true, Set.of(), false)
        ));
        final var recorded = List.of(
            new CoverageComparator.RecordedOperation("/resources", "GET", 200,
                Set.of(), Set.of(), false, Set.of("application/json")),
            new CoverageComparator.RecordedOperation("/resources", "GET", 500,
                Set.of(), Set.of(), false, Set.of("application/json")),
            new CoverageComparator.RecordedOperation("/uncovered", "GET", 500,
                Set.of(), Set.of(), false, Set.of("application/json")),
            new CoverageComparator.RecordedOperation("/not-in-spec", "GET", 404,
                Set.of(), Set.of(), false, Set.of("application/json"))
        );
        return new CoverageComparator(spec, recorded).analyze();
    }

    private String generate(final Path dir) {
        new HtmlReportGenerator(sampleResult(), "3.0.3",
            "examples/openapi.json", 4).generate(dir.toString());
        try {
            return Files.readString(dir.resolve("coverage-report.html"));
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String generateCustom(final Path dir, final String reportName) {
        new HtmlReportGenerator(sampleResult(), "3.0.3",
            "examples/openapi.json", 4, reportName).generate(dir.toString());
        try {
            return Files.readString(dir.resolve(reportName));
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void writesSelfContainedHtmlWithNoLeftoverPlaceholders(
            @TempDir final Path dir) {
        final String html = generate(dir);
        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).doesNotContain("{{");
        assertThat(html).doesNotContain("}}");
    }

    @Test
    void rendersTagAndGenerationInfoAndDeprecatedAndUndeclared(
            @TempDir final Path dir) {
        final String html = generate(dir);
        assertThat(html).contains("Coverage by tag");
        assertThat(html).contains("Resources");
        assertThat(html).contains("Generation info");
        // recorded requests parsed count is surfaced
        assertThat(html).contains("Recorded requests parsed");
        // an undeclared status (500) is surfaced as a badge
        assertThat(html).contains("badge-undeclared");
        // a deprecated operation is flagged
        assertThat(html).contains("badge-deprecated");
    }

    @Test
    void embedsThemeAwareStylesAndInteractivity(@TempDir final Path dir) {
        final String html = generate(dir);
        assertThat(html).contains("prefers-color-scheme: dark");
        assertThat(html).contains("id=\"searchBox\"");
        assertThat(html).contains("id=\"opsTable\"");
    }

    @Test
    void rendersUnmatchedSectionAndCardScrollHandler(@TempDir final Path dir) {
        final String html = generate(dir);
        assertThat(html).contains("data-filter=\"unmatched\"");
        assertThat(html).contains("id=\"unmatchedRequests\"");
        assertThat(html).contains("filterVal === 'unmatched'");
        assertThat(html).contains("/not-in-spec");
    }

    @Test
    void distinguishesCalledButUncoveredFromNoCall(
            @TempDir final Path dir) {
        final String html = generate(dir);
        assertThat(html).contains("Called / uncovered");
        assertThat(html).contains("data-cov=\"empty\"");
        assertThat(html).contains("No recorded calls matched this operation");
    }

    @Test
    void writesCustomHtmlReportName(@TempDir final Path dir) {
        final String html = generateCustom(dir, "api-coverage.html");
        assertThat(html).contains("<title>API Coverage Report</title>");
    }
}
