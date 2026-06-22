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
        return op(method, path, statuses, tag, deprecated, false, params);
    }

    private static ApiOperation op(final String method, final String path,
                                   final Set<Integer> statuses,
                                   final String tag, final boolean deprecated,
                                   final boolean hasDeclaredRequestBody,
                                   final ParameterSpec... params) {
        return new ApiOperation(path, method, statuses, List.of(params),
            tag, "", deprecated, Set.of(), hasDeclaredRequestBody);
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
            op("GET", "/resources", Set.of(200, 400), "Resources", false,
                new ParameterSpec("status", "query", false)),
            op("POST", "/resources", Set.of(201, 400), "Resources", false, true),
            op("GET", "/resources/{id}", Set.of(200, 404), "Resources", false),
            op("DELETE", "/resources/{id}", Set.of(204, 404), "Resources", false),
            op("GET", "/entities/{id}", Set.of(200, 404), "Entities", true)
        ));
        final List<RecordedOperation> recorded = List.of(
            // base path "/api" must be stripped to match "/resources"
            rec("/api/resources", "GET", 200, false, "status"),
            rec("/resources", "POST", 201, true),
            rec("/resources", "POST", 500, true),          // 500 undeclared
            rec("/resources/123", "GET", 200, false),
            rec("/entities/55", "GET", 200, false),
            rec("/entities/55", "GET", 404, false)     // fully covers op
        );
        return new CoverageComparator(spec, recorded).analyze();
    }

    @Test
    void classifiesOperationsByCoverage() {
        final DetailedCoverageResult r = analyzeFixture();
        assertThat(r.totalOperations()).isEqualTo(5);
        assertThat(r.fullCoverageCount()).isEqualTo(1);     // GET /entities/{id}
        assertThat(r.partialCoverageCount()).isEqualTo(3);
        assertThat(r.noCallCount()).isEqualTo(1);           // DELETE /resources/{id}
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
            .filter(o -> o.method().equals("POST") && o.path().equals("/resources"))
            .findFirst().orElseThrow();
        assertThat(post.undeclaredStatuses()).containsExactly(500);
        // 500 must not appear as a counted condition.
        assertThat(post.conditions())
            .noneMatch(c -> c.description().contains("500"));
    }

    @Test
    void stripsDeploymentBasePathWhenMatching() {
        final DetailedCoverageResult r = analyzeFixture();
        final OperationDetail getResources = r.operations().stream()
            .filter(o -> o.method().equals("GET") && o.path().equals("/resources"))
            .findFirst().orElseThrow();
        // recorded "/api/resources" matched the spec "/resources"
        assertThat(getResources.callCount()).isEqualTo(1);
        assertThat(getResources).returns(true, OperationDetail::hasCalls);
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
            .containsExactlyInAnyOrder("Resources", "Entities");
        assertThat(r.tagSummaries().get("Resources").total()).isEqualTo(4);
        assertThat(r.tagSummaries().get("Entities").total()).isEqualTo(1);
        assertThat(r.tagSummaries().get("Entities").full()).isEqualTo(1);
    }

    @Test
    void pathToRegexMatchesTemplatedSegment() {
        final Pattern p = Pattern.compile(
            CoverageComparator.pathToRegex("/resources/{id}"));
        assertThat("/resources/123").matches(p);
        assertThat("/resources/abc").matches(p);
        assertThat("/resources/1/2").doesNotMatch(p);
        assertThat("/resources").doesNotMatch(p);
    }

    @Test
    void pathToRegexEscapesStaticRegexCharacters() {
        final Pattern p = Pattern.compile(
            CoverageComparator.pathToRegex("/v1.0/resources+/{id}"));
        assertThat("/v1.0/resources+/123").matches(p);
        assertThat("/v1X0/resources+/123").doesNotMatch(p);
        assertThat("/v1.0/resourcess/123").doesNotMatch(p);
    }

    @Test
    void parsesCoverageFile(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve("x-coverage.json");
        Files.writeString(file, """
            { "format": "openapi-coverage-v1", "paths": {
              "/resources": { "post": {
                "parameters": [ { "name": "trace", "in": "query",
                  "value": "1" } ],
                "requestBody": { "present": true },
                "responses": { "201": {} } } } } }
            """);
        final RecordedOperation op =
            CoverageComparator.parseCoverageFile(file.toFile());
        assertThat(op)
            .returns("/resources", RecordedOperation::originalPath)
            .returns("POST", RecordedOperation::method)
            .returns(201, RecordedOperation::statusCode)
            .returns(true, RecordedOperation::hasBody);
        assertThat(op.paramNames()).contains("trace");
    }

    @Test
    void returnsEmptyForMissingCoverageDirectory() {
        assertThat(CoverageComparator.readCoverageFiles(
            new File("/no/such/dir").getPath())).isEmpty();
    }

    @Test
    void tracksUnmatchedRecordedOperations() {
        final ApiSpec spec = new ApiSpec("3.0.3", List.of(
            op("GET", "/resources", Set.of(200), "Resources", false)
        ));
        final List<RecordedOperation> recorded = List.of(
            rec("/resources", "GET", 200, false),
            rec("/unmatched/service/action/123", "POST", 200, false),
            rec("/unmatched/service/metric", "GET", 400, false)
        );
        final DetailedCoverageResult r =
            new CoverageComparator(spec, recorded).analyze();
        assertThat(r.unmatchedRecordedOps()).hasSize(2);
        assertThat(r.unmatchedRecordedOps())
            .extracting(RecordedOperation::originalPath)
            .containsExactlyInAnyOrder(
                "/unmatched/service/action/123",
                "/unmatched/service/metric");
    }

    @Test
    void noUnmatchedWhenAllPathsMatch() {
        final DetailedCoverageResult r = analyzeFixture();
        assertThat(r.unmatchedRecordedOps()).isEmpty();
    }

    @Test
    void countsRequestBodyConditionOnlyWhenSpecDeclaresBody() {
        final ApiSpec spec = new ApiSpec("3.1.0", List.of(
            op("POST", "/search", Set.of(200), "Search", false, false),
            op("POST", "/resources", Set.of(201), "Resources", false, true)
        ));
        final DetailedCoverageResult r =
            new CoverageComparator(spec, List.of()).analyze();

        final OperationDetail search = r.operations().stream()
            .filter(o -> o.path().equals("/search")).findFirst().orElseThrow();
        final OperationDetail resources = r.operations().stream()
            .filter(o -> o.path().equals("/resources")).findFirst().orElseThrow();

        assertThat(search.conditions())
            .noneMatch(c -> c.category().equals("Request Body Availability"));
        assertThat(resources.conditions())
            .anySatisfy(c -> {
                assertThat(c.category()).isEqualTo("Request Body Availability");
                assertThat(c).returns(false,
                    CoverageComparator.ConditionResult::covered);
            });
    }

    @Test
    void swaggerBodyParameterIsNotCountedAsParameterValueCondition() {
        final ApiSpec spec = new ApiSpec("2.0", List.of(
            op("POST", "/items", Set.of(201), "Items", false, true,
                new ParameterSpec("item", "body", true),
                new ParameterSpec("trace", "query", false))
        ));
        final DetailedCoverageResult r = new CoverageComparator(
            spec, List.of(rec("/items", "POST", 201, true, "trace"))).analyze();

        final OperationDetail items = r.operations().get(0);
        assertThat(items.definedParameters()).containsExactly("trace");
        assertThat(items.conditions())
            .noneSatisfy(c -> assertThat(c.description()).contains("<item>"))
            .anySatisfy(c -> assertThat(c.description()).contains("<trace>"));
    }

    @Test
    void onlyDeclaredStatusAddsUncoveredConditionForUndeclaredRuntimeStatus() {
        final ApiSpec spec = new ApiSpec("3.1.0", List.of(
            op("GET", "/resources", Set.of(200), "Resources", false)
        ));
        final CoverageConfig cfg = new CoverageConfig(
            List.of(), Set.of(), false, true,
            "coverage-report.html", "coverage-report.json",
            false, Set.of(), true, true, true, true);
        final DetailedCoverageResult r = new CoverageComparator(
            spec, List.of(rec("/resources", "GET", 500, false)), cfg).analyze();

        final OperationDetail resources = r.operations().get(0);
        assertThat(resources.conditions())
            .anySatisfy(c -> {
                assertThat(c.category()).isEqualTo("Only Declared Status");
                assertThat(c).returns(false,
                    CoverageComparator.ConditionResult::covered);
                assertThat(c.details()).isEqualTo("Undeclared status: 500");
            });
        assertThat(r.conditionTypes().get("Only Declared Status").total())
            .isEqualTo(1);
    }

    @Test
    void advisorySuggestionsAreGeneratedSeparatelyFromCoverageConditions() {
        final ApiSpec spec = new ApiSpec("3.1.0", List.of(
            new ApiOperation(
                "/resources", "POST", Set.of(201),
                List.of(new ParameterSpec("trace", "query", true)),
                "Resources", "", false, Set.of("application/json"), true)
        ));
        final CoverageConfig cfg = new CoverageConfig(
            List.of(), Set.of(), false, false,
            "coverage-report.html", "coverage-report.json",
            true, Set.of(401), true, true, true, true);
        final DetailedCoverageResult r = new CoverageComparator(
            spec, List.of(rec("/resources", "POST", 201, true, "trace")), cfg)
            .analyze();

        assertThat(r.suggestions())
            .anySatisfy(s -> {
                assertThat(s.type()).isEqualTo("status");
                assertThat(s.value()).isEqualTo("401");
            })
            .anySatisfy(s -> assertThat(s.type()).isEqualTo("empty-parameter"))
            .anySatisfy(s -> assertThat(s.type()).isEqualTo("blank-parameter"))
            .anySatisfy(s -> assertThat(s.type())
                .isEqualTo("missing-required-parameter"))
            .anySatisfy(s -> assertThat(s.type())
                .isEqualTo("invalid-media-type"));
        assertThat(r.conditionTypes()).doesNotContainKey("Only Declared Status");
    }

    private static org.assertj.core.data.Offset<Double> within(final double v) {
        return org.assertj.core.data.Offset.offset(v);
    }
}
