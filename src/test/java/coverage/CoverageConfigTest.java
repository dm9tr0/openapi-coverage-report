package coverage;

import static org.assertj.core.api.Assertions.assertThat;

import coverage.OpenApiSpecParser.ApiOperation;
import coverage.OpenApiSpecParser.ApiSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoverageConfigTest {

    private ApiOperation op(final String path, final String method,
            final boolean deprecated) {
        return new ApiOperation(path, method, Set.of(200), List.of(),
            "Tag", "", deprecated, Set.of(), false);
    }

    @Test
    void blankPathYieldsEmptyConfig() {
        final CoverageConfig cfg = CoverageConfig.load("   ");
        assertThat(cfg).returns(false, CoverageConfig::ignoreDeprecated);
        assertThat(cfg.ignoreStatuses()).isEmpty();
        assertThat(cfg.ignoreOperations()).isEmpty();
    }

    @Test
    void missingFileYieldsEmptyConfig(@TempDir final Path dir) {
        final CoverageConfig cfg
            = CoverageConfig.load(dir.resolve("nope.conf").toString());
        assertThat(cfg.ignoreOperations()).isEmpty();
        assertThat(cfg).returns(false, CoverageConfig::ignoreDeprecated);
    }

    @Test
    void parsesFlatKeyValueConfig(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve("openapi-coverage.conf");
        Files.writeString(file, """
            # sample config — comments and blank lines are ignored

            ignore-deprecated = true
            ignore-status = 500
            ignore-status = 503   # trailing comment
            ignore-operation = POST /internal/.*
            ignore-operation = /admin/.*
            only-declared-status = true
            html-report-name = api-coverage.html
            json-report-name = api-coverage.json
            suggest-test-gaps = true
            suggest-status = 401
            suggest-status = 403
            suggest-empty-parameter = false
            suggest-blank-parameter = false
            suggest-missing-required-parameter = false
            suggest-invalid-media-type = false
            unknown-key = whatever
            """);

        final CoverageConfig cfg = CoverageConfig.load(file.toString());

        assertThat(cfg).returns(true, CoverageConfig::ignoreDeprecated);
        assertThat(cfg.ignoreStatuses()).containsExactlyInAnyOrder(500, 503);
        assertThat(cfg.ignoreOperations()).hasSize(2);
        assertThat(cfg).returns(true, CoverageConfig::onlyDeclaredStatus)
            .returns("api-coverage.html", CoverageConfig::htmlReportName)
            .returns("api-coverage.json", CoverageConfig::jsonReportName)
            .returns(true, CoverageConfig::suggestTestGaps)
            .returns(false, CoverageConfig::suggestEmptyParameter)
            .returns(false, CoverageConfig::suggestBlankParameter)
            .returns(false, CoverageConfig::suggestMissingRequiredParameter)
            .returns(false, CoverageConfig::suggestInvalidMediaType);
        assertThat(cfg.suggestStatuses()).containsExactlyInAnyOrder(401, 403);
    }

    @Test
    void ignoresDeprecatedAndMatchedOperations(@TempDir final Path dir)
            throws Exception {
        final Path file = dir.resolve("c.conf");
        Files.writeString(file, """
            ignore-deprecated = true
            ignore-status = 500
            ignore-operation = POST /internal/.*
            ignore-operation = /admin/.*
            """);
        final CoverageConfig cfg = CoverageConfig.load(file.toString());

        assertThat(cfg.ignoreStatuses()).contains(500).doesNotContain(404);
        assertThat(List.of(
            op("/anything", "GET", true),
            op("/internal/sync", "POST", false),
            op("/internal/sync", "GET", false),
            op("/admin/x", "DELETE", false),
            op("/resources", "GET", false)))
            .filteredOn(cfg::ignores)
            .extracting(ApiOperation::path)
            .containsExactly("/anything", "/internal/sync", "/admin/x");
    }

    @Test
    void emptyConfigIgnoresNothing() {
        final CoverageConfig cfg = CoverageConfig.empty();
        assertThat(cfg.ignoreOperations()).isEmpty();
        assertThat(cfg.ignoreStatuses()).doesNotContain(500);
    }

    @Test
    void comparatorExcludesIgnoredDeprecatedOperations() {
        final ApiSpec spec = new ApiSpec("3.1.0", List.of(
            new ApiOperation("/resources", "GET", Set.of(200), List.of(),
                "Resources", "", false, Set.of(), false),
            new ApiOperation("/legacy", "GET", Set.of(200), List.of(),
                "Legacy", "", true, Set.of(), false)));
        final List<CoverageComparator.RecordedOperation> none = List.of();

        assertThat(new CoverageComparator(spec, none).analyze()
            .totalOperations()).isEqualTo(2);

        final CoverageConfig cfg
            = new CoverageConfig(
                List.of(), Set.of(), true, false,
                "coverage-report.html", "coverage-report.json",
                false, Set.of(), true, true, true, true);
        final var filtered = new CoverageComparator(spec, none, cfg).analyze();
        assertThat(filtered.totalOperations()).isEqualTo(1);
        assertThat(filtered.operations().get(0).path()).isEqualTo("/resources");
    }

    @Test
    void comparatorExcludesIgnoredStatusConditions() {
        final ApiSpec spec = new ApiSpec("3.1.0", List.of(
            new ApiOperation("/resources", "GET", Set.of(200, 500), List.of(),
                "Resources", "", false, Set.of(), false)));
        final List<CoverageComparator.RecordedOperation> none = List.of();

        final int baseline = new CoverageComparator(spec, none).analyze()
            .totalConditions();
        final CoverageConfig cfg
            = new CoverageConfig(
                List.of(), Set.of(500), false, false,
                "coverage-report.html", "coverage-report.json",
                false, Set.of(), true, true, true, true);
        final int filtered = new CoverageComparator(spec, none, cfg).analyze()
            .totalConditions();

        assertThat(filtered).isLessThan(baseline);
    }

    @Test
    void defaultSuggestionStatusesAreCommonNegativeStatuses() {
        assertThat(CoverageConfig.empty().effectiveSuggestStatuses())
            .containsExactlyInAnyOrder(400, 401, 403, 404);
    }
}
