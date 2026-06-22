package coverage;

import static org.assertj.core.api.Assertions.assertThat;

import coverage.CoverageComparator.DetailedCoverageResult;
import coverage.CoverageComparator.RecordedOperation;
import coverage.OpenApiSpecParser.ApiOperation;
import coverage.OpenApiSpecParser.ApiSpec;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompatibilityFixtureTest {

    @Test
    void parsesOpenApi31ReferenceFixture() throws Exception {
        final ApiSpec spec = parse("openapi31-refs.json");
        final ApiOperation get = spec.operations().get(0);

        assertThat(spec.version()).isEqualTo("3.1.0");
        assertThat(get)
            .returns("/resources/{id}", ApiOperation::path)
            .returns("GET", ApiOperation::method)
            .returns("Resources", ApiOperation::tag);
        assertThat(get.statusCodes()).containsExactlyInAnyOrder(200, 404);
        assertThat(get.mediaTypes()).containsExactly("application/json");
        assertThat(get.parameters())
            .extracting(OpenApiSpecParser.ParameterSpec::name)
            .containsExactlyInAnyOrder("X-Trace-Id", "verbose", "id");
    }

    @Test
    void parsesSwagger20CompatibilityFixture() throws Exception {
        final ApiSpec spec = parse("swagger2-basic.json");
        final ApiOperation post = spec.operations().get(0);

        assertThat(spec.version()).isEqualTo("2.0");
        assertThat(post)
            .returns("/items/{id}", ApiOperation::path)
            .returns("POST", ApiOperation::method)
            .returns("Items", ApiOperation::tag)
            .returns(true, ApiOperation::hasDeclaredRequestBody);
        assertThat(post.statusCodes()).containsExactlyInAnyOrder(201, 400);
        assertThat(post.mediaTypes()).containsExactly("application/json");
    }

    @Test
    void fixtureConfigEnablesOnlyDeclaredStatusRule() throws Exception {
        final ApiSpec spec = parse("openapi31-refs.json");
        final CoverageConfig cfg = CoverageConfig.load(
            fixturePath("only-declared-status.conf").toString());
        final DetailedCoverageResult result = new CoverageComparator(
            spec,
            List.of(new RecordedOperation(
                "/resources/123", "GET", 500,
                Set.of(), Set.of(), false, Set.of())),
            cfg).analyze();

        assertThat(result.conditionTypes())
            .containsKey("Only Declared Status");
        assertThat(result.operations().get(0).conditions())
            .anySatisfy(c -> {
                assertThat(c.category()).isEqualTo("Only Declared Status");
                assertThat(c.details()).isEqualTo("Undeclared status: 500");
            });
    }

    @Test
    void fixtureConfigEnablesAdvisorySuggestions() throws Exception {
        final ApiSpec spec = parse("swagger2-basic.json");
        final CoverageConfig cfg = CoverageConfig.load(
            fixturePath("suggestions.conf").toString());
        final DetailedCoverageResult result = new CoverageComparator(
            spec,
            List.of(new RecordedOperation(
                "/items/123", "POST", 201,
                Set.of("item"), Set.of("item"), true, Set.of("application/json"))),
            cfg).analyze();

        assertThat(result.suggestions())
            .anySatisfy(s -> {
                assertThat(s.type()).isEqualTo("status");
                assertThat(s.value()).isEqualTo("401");
            })
            .anySatisfy(s -> assertThat(s.type())
                .isEqualTo("invalid-media-type"));
    }

    private static ApiSpec parse(final String fixtureName) throws Exception {
        return new OpenApiSpecParser(Files.readString(fixturePath(fixtureName)))
            .spec();
    }

    private static Path fixturePath(final String fixtureName)
            throws URISyntaxException {
        return Path.of(CompatibilityFixtureTest.class.getResource(
            "/fixtures/" + fixtureName).toURI());
    }
}
