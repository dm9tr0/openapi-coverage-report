package coverage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import coverage.CoverageComparator.DetailedCoverageResult;
import coverage.OpenApiSpecParser.ApiOperation;
import coverage.OpenApiSpecParser.ApiSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonReportGeneratorTest {

    private DetailedCoverageResult sampleResult() {
        final ApiSpec spec = new ApiSpec("3.1.0", List.of(
            new ApiOperation("/orders", "GET", Set.of(200, 400),
                List.of(), "Orders", "List orders", false, Set.of())));
        final var recorded = List.of(
            new CoverageComparator.RecordedOperation("/orders", "GET", 200,
                Set.of(), Set.of(), false, Set.of("application/json")));
        return new CoverageComparator(spec, recorded).analyze();
    }

    @Test
    void writesJsonWithMetaSummaryAndOperations(@TempDir final Path dir)
            throws Exception {
        new JsonReportGenerator(sampleResult(), "3.1.0", "spec-source", 1)
            .generate(dir.toString());

        final Path json = dir.resolve("coverage-report.json");
        assertThat(Files.exists(json)).isTrue();

        final JsonNode root = new ObjectMapper().readTree(Files.readString(json));
        assertThat(root.path("meta").path("specVersion").asText())
            .isEqualTo("3.1.0");
        assertThat(root.path("meta").path("specSource").asText())
            .isEqualTo("spec-source");
        assertThat(root.path("meta").path("recordedOperations").asInt())
            .isEqualTo(1);
        assertThat(root.path("summary").path("totalConditions").asInt())
            .isGreaterThan(0);
        assertThat(root.path("summary").has("coveredPercent")).isTrue();
        assertThat(root.path("operations")).isNotEmpty();
        assertThat(root.path("operations").get(0).path("path").asText())
            .isEqualTo("/orders");
        assertThat(root.path("operations").get(0).path("method").asText())
            .isEqualTo("GET");
    }
}
