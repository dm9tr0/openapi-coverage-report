package coverage;

import static org.assertj.core.api.Assertions.assertThat;

import coverage.OpenApiSpecParser.ApiOperation;
import coverage.OpenApiSpecParser.ApiSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenApiSpecParserTest {

    private static final String SPEC = """
        {
          "openapi": "3.0.3",
          "info": { "title": "T", "version": "1.0.0" },
          "paths": {
            "/orders": {
              "get": {
                "tags": ["Orders"],
                "summary": "List orders",
                "parameters": [
                  { "name": "status", "in": "query", "required": false }
                ],
                "responses": { "200": {}, "400": {} }
              },
              "post": {
                "tags": ["Orders", "Internal"],
                "summary": "Create order",
                "deprecated": true,
                "responses": { "201": {} }
              }
            },
            "/widgets/{id}": {
              "get": { "responses": { "200": {} } }
            }
          }
        }
        """;

    private ApiOperation op(final ApiSpec spec, final String method,
                            final String path) {
        return spec.operations().stream()
            .filter(o -> o.method().equals(method) && o.path().equals(path))
            .findFirst().orElseThrow();
    }

    @Test
    void parsesVersionAndOperationCount() {
        final ApiSpec spec = new OpenApiSpecParser(SPEC).spec();
        assertThat(spec.version()).isEqualTo("3.0.3");
        assertThat(spec.operations()).hasSize(3);
    }

    @Test
    void parsesStatusCodesTagSummaryAndParameters() {
        final ApiSpec spec = new OpenApiSpecParser(SPEC).spec();
        final ApiOperation get = op(spec, "GET", "/orders");
        assertThat(get.statusCodes()).containsExactlyInAnyOrder(200, 400);
        assertThat(get.tag()).isEqualTo("Orders");
        assertThat(get.summary()).isEqualTo("List orders");
        assertThat(get.deprecated()).isFalse();
        assertThat(get.parameters())
            .anySatisfy(p -> {
                assertThat(p.name()).isEqualTo("status");
                assertThat(p.in()).isEqualTo("query");
            });
    }

    @Test
    void usesFirstTagAndReadsDeprecatedFlag() {
        final ApiSpec spec = new OpenApiSpecParser(SPEC).spec();
        final ApiOperation post = op(spec, "POST", "/orders");
        assertThat(post.tag()).isEqualTo("Orders");
        assertThat(post.deprecated()).isTrue();
    }

    @Test
    void defaultsUntaggedAndEmptySummaryAndExtractsPathParams() {
        final ApiSpec spec = new OpenApiSpecParser(SPEC).spec();
        final ApiOperation widget = op(spec, "GET", "/widgets/{id}");
        assertThat(widget.tag()).isEqualTo("Untagged");
        assertThat(widget.summary()).isEmpty();
        assertThat(widget.parameters())
            .anySatisfy(p -> {
                assertThat(p.name()).isEqualTo("id");
                assertThat(p.in()).isEqualTo("path");
                assertThat(p.required()).isTrue();
            });
    }

    @Test
    void extractsTemplatedPathParameters() {
        assertThat(OpenApiSpecParser.extractPathTemplateParams("/a/{x}/b/{y}"))
            .containsExactly("x", "y");
        assertThat(OpenApiSpecParser.extractPathTemplateParams("/no/params"))
            .isEmpty();
    }

    @Test
    void parsesOpenApi31() {
        final String spec31 = """
            { "openapi": "3.1.0", "paths": {
              "/ping": { "get": { "responses": { "200": {} } } } } }
            """;
        final ApiSpec spec = new OpenApiSpecParser(spec31).spec();
        assertThat(spec.version()).isEqualTo("3.1.0");
        assertThat(spec.operations()).hasSize(1);
    }
}
