package coverage;

import static org.assertj.core.api.Assertions.assertThat;

import coverage.CoverageComparator.DetailedCoverageResult;
import coverage.CoverageComparator.OperationDetail;
import coverage.CoverageComparator.RecordedOperation;
import coverage.OpenApiSpecParser.ApiOperation;
import coverage.OpenApiSpecParser.ApiSpec;
import coverage.OpenApiSpecParser.ParameterSpec;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoverageComparatorTest {

    private static ApiOperation op(final String method, final String path,
                                   final Set<Integer> statuses,
                                   final String tag, final boolean deprecated,
                                   final ParameterSpec... params) {
        return new ApiOperation(path, method, statuses, List.of(params),
            tag, "", deprecated, Set.of());
    }

    private static RecordedOperation rec(final String path, final String method,
                                         final int status, final boolean body,
                                         final String... params) {
        return new RecordedOperation(path, method, status,
            Set.of(params), Set.of(params), body, Set.of("application/json"));
    }

    /** A spec + recorded calls exercising every coverage outcome. */
    private DetailedCoverageResult analyzeFixture() {
        final ApiSpec spec = new ApiSpec("3.0.3", List.of(
            op("GET", "/orders", Set.of(200, 400), "Orders", false,
                new ParameterSpec("status", "query", false)),
            op("POST", "/orders", Set.of(201, 400), "Orders", false),
            op("GET", "/orders/{id}", Set.of(200, 404), "Orders", false),
            op("DELETE", "/orders/{id}", Set.of(204, 404), "Orders", false),
            op("GET", "/customers/{id}", Set.of(200, 404), "Customers", true)
        ));
        final List<RecordedOperation> recorded = List.of(
            // base path "/api" must be stripped to match "/orders"
            rec("/api/orders", "GET", 200, false, "status"),
            rec("/orders", "POST", 201, true),
            rec("/orders", "POST", 500, true),          // 500 undeclared
            rec("/orders/123", "GET", 200, false),
            rec("/customers/55", "GET", 200, false),
            rec("/customers/55", "GET", 404, false)     // fully covers op
        );
        return new CoverageComparator(spec, recorded).analyze();
    }

    @Test
    void classifiesOperationsByCoverage() {
        final DetailedCoverageResult r = analyzeFixture();
        assertThat(r.totalOperations()).isEqualTo(5);
        assertThat(r.fullCoverageCount()).isEqualTo(1);     // GET /customers/{id}
        assertThat(r.partialCoverageCount()).isEqualTo(3);
        assertThat(r.noCallCount()).isEqualTo(1);           // DELETE /orders/{id}
    }

    @Test
    void countsConditionsAndCoverage() {
        final DetailedCoverageResult r = analyzeFixture();
        // 3 + 3 + 2 + 2 + 2 conditions; only-declared is NOT a condition.
        assertThat(r.totalConditions()).isEqualTo(12);
        assertThat(r.coveredConditions()).isEqualTo(7);
        assertThat(r.totalCoveredPercent()).isCloseTo(58.33, within(0.1));
    }

    @Test
    void detectsUndeclaredStatusesWithoutCountingThem() {
        final DetailedCoverageResult r = analyzeFixture();
        assertThat(r.undeclaredOpsCount()).isEqualTo(1);
        final OperationDetail post = r.operations().stream()
            .filter(o -> o.method().equals("POST") && o.path().equals("/orders"))
            .findFirst().orElseThrow();
        assertThat(post.undeclaredStatuses()).containsExactly(500);
        // 500 must not appear as a counted condition.
        assertThat(post.conditions())
            .noneMatch(c -> c.description().contains("500"));
    }

    @Test
    void stripsDeploymentBasePathWhenMatching() {
        final DetailedCoverageResult r = analyzeFixture();
        final OperationDetail getOrders = r.operations().stream()
            .filter(o -> o.method().equals("GET") && o.path().equals("/orders"))
            .findFirst().orElseThrow();
        // recorded "/api/orders" matched the spec "/orders"
        assertThat(getOrders.callCount()).isEqualTo(1);
        assertThat(getOrders.hasCalls()).isTrue();
    }

    @Test
    void reportsDeprecatedOperations() {
        final DetailedCoverageResult r = analyzeFixture();
        assertThat(r.deprecatedOpsCount()).isEqualTo(1);
    }

    @Test
    void buildsPerTagSummaries() {
        final DetailedCoverageResult r = analyzeFixture();
        assertThat(r.tagSummaries().keySet())
            .containsExactlyInAnyOrder("Orders", "Customers");
        assertThat(r.tagSummaries().get("Orders").total()).isEqualTo(4);
        assertThat(r.tagSummaries().get("Customers").total()).isEqualTo(1);
        assertThat(r.tagSummaries().get("Customers").full()).isEqualTo(1);
    }

    @Test
    void pathToRegexMatchesTemplatedSegment() {
        final Pattern p = Pattern.compile(
            CoverageComparator.pathToRegex("/orders/{id}"));
        assertThat(p.matcher("/orders/123").matches()).isTrue();
        assertThat(p.matcher("/orders/abc").matches()).isTrue();
        assertThat(p.matcher("/orders/1/2").matches()).isFalse();
        assertThat(p.matcher("/orders").matches()).isFalse();
    }

    @Test
    void parsesCoverageFile(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve("x-coverage.json");
        Files.writeString(file, """
            { "format": "openapi-coverage-v1", "paths": {
              "/orders": { "post": {
                "parameters": [ { "name": "trace", "in": "query",
                  "value": "1" } ],
                "requestBody": { "present": true },
                "responses": { "201": {} } } } } }
            """);
        final RecordedOperation op =
            CoverageComparator.parseCoverageFile(file.toFile());
        assertThat(op).isNotNull();
        assertThat(op.originalPath()).isEqualTo("/orders");
        assertThat(op.method()).isEqualTo("POST");
        assertThat(op.statusCode()).isEqualTo(201);
        assertThat(op.paramNames()).contains("trace");
        assertThat(op.hasBody()).isTrue();
    }

    @Test
    void returnsEmptyForMissingCoverageDirectory() {
        assertThat(CoverageComparator.readCoverageFiles(
            new File("/no/such/dir").getPath())).isEmpty();
    }

    @Test
    void tracksUnmatchedRecordedOperations() {
        final ApiSpec spec = new ApiSpec("3.0.3", List.of(
            op("GET", "/orders", Set.of(200), "Orders", false)
        ));
        final List<RecordedOperation> recorded = List.of(
            rec("/orders", "GET", 200, false),
            rec("/translationlayerapi/v6/periods/favr/submit/123", "POST", 200, false),
            rec("/translationlayerapi/v6/periods/submission/odometer", "GET", 400, false)
        );
        final DetailedCoverageResult r =
            new CoverageComparator(spec, recorded).analyze();
        assertThat(r.unmatchedRecordedOps()).hasSize(2);
        assertThat(r.unmatchedRecordedOps())
            .extracting(RecordedOperation::originalPath)
            .containsExactlyInAnyOrder(
                "/translationlayerapi/v6/periods/favr/submit/123",
                "/translationlayerapi/v6/periods/submission/odometer");
    }

    @Test
    void noUnmatchedWhenAllPathsMatch() {
        final DetailedCoverageResult r = analyzeFixture();
        assertThat(r.unmatchedRecordedOps()).isEmpty();
    }

    private static org.assertj.core.data.Offset<Double> within(final double v) {
        return org.assertj.core.data.Offset.offset(v);
    }
}
