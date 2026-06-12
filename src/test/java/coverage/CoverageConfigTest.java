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
            "Tag", "", deprecated, Set.of());
    }

    @Test
    void blankPathYieldsEmptyConfig() {
        final CoverageConfig cfg = CoverageConfig.load("   ");
        assertThat(cfg.ignoreDeprecated()).isFalse();
        assertThat(cfg.ignoreStatuses()).isEmpty();
        assertThat(cfg.ignoreOperations()).isEmpty();
    }

    @Test
    void missingFileYieldsEmptyConfig(@TempDir final Path dir) {
        final CoverageConfig cfg
            = CoverageConfig.load(dir.resolve("nope.conf").toString());
        assertThat(cfg.ignoreOperations()).isEmpty();
        assertThat(cfg.ignoreDeprecated()).isFalse();
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
            unknown-key = whatever
            """);

        final CoverageConfig cfg = CoverageConfig.load(file.toString());

        assertThat(cfg.ignoreDeprecated()).isTrue();
        assertThat(cfg.ignoreStatuses()).containsExactlyInAnyOrder(500, 503);
        assertThat(cfg.ignoreOperations()).hasSize(2);
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

        assertThat(cfg.ignoresStatus(500)).isTrue();
        assertThat(cfg.ignoresStatus(404)).isFalse();
        assertThat(cfg.ignores(op("/anything", "GET", true))).isTrue();
        assertThat(cfg.ignores(op("/internal/sync", "POST", false))).isTrue();
        assertThat(cfg.ignores(op("/internal/sync", "GET", false))).isFalse();
        assertThat(cfg.ignores(op("/admin/x", "DELETE", false))).isTrue();
        assertThat(cfg.ignores(op("/orders", "GET", false))).isFalse();
    }

    @Test
    void emptyConfigIgnoresNothing() {
        final CoverageConfig cfg = CoverageConfig.empty();
        assertThat(cfg.ignores(op("/x", "GET", true))).isFalse();
        assertThat(cfg.ignoresStatus(500)).isFalse();
    }

    @Test
    void comparatorExcludesIgnoredDeprecatedOperations() {
        final ApiSpec spec = new ApiSpec("3.1.0", List.of(
            new ApiOperation("/orders", "GET", Set.of(200), List.of(),
                "Orders", "", false, Set.of()),
            new ApiOperation("/legacy", "GET", Set.of(200), List.of(),
                "Legacy", "", true, Set.of())));
        final List<CoverageComparator.RecordedOperation> none = List.of();

        assertThat(new CoverageComparator(spec, none).analyze()
            .totalOperations()).isEqualTo(2);

        final CoverageConfig cfg
            = new CoverageConfig(List.of(), Set.of(), true);
        final var filtered = new CoverageComparator(spec, none, cfg).analyze();
        assertThat(filtered.totalOperations()).isEqualTo(1);
        assertThat(filtered.operations().get(0).path()).isEqualTo("/orders");
    }

    @Test
    void comparatorExcludesIgnoredStatusConditions() {
        final ApiSpec spec = new ApiSpec("3.1.0", List.of(
            new ApiOperation("/orders", "GET", Set.of(200, 500), List.of(),
                "Orders", "", false, Set.of())));
        final List<CoverageComparator.RecordedOperation> none = List.of();

        final int baseline = new CoverageComparator(spec, none).analyze()
            .totalConditions();
        final CoverageConfig cfg
            = new CoverageConfig(List.of(), Set.of(500), false);
        final int filtered = new CoverageComparator(spec, none, cfg).analyze()
            .totalConditions();

        assertThat(filtered).isLessThan(baseline);
    }
}
