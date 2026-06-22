package coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            "/resources": {
              "get": {
                "tags": ["Resources"],
                "summary": "List resources",
                "parameters": [
                  { "name": "status", "in": "query", "required": false }
                ],
                "responses": { "200": {}, "400": {} }
              },
              "post": {
                "tags": ["Resources", "Internal"],
                "summary": "Create resource",
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
        final ApiOperation get = op(spec, "GET", "/resources");
        assertThat(get.statusCodes()).containsExactlyInAnyOrder(200, 400);
        assertThat(get.tag()).isEqualTo("Resources");
        assertThat(get.summary()).isEqualTo("List resources");
        assertThat(get).returns(false, ApiOperation::deprecated);
        assertThat(get.parameters())
            .anySatisfy(p -> {
                assertThat(p.name()).isEqualTo("status");
                assertThat(p.in()).isEqualTo("query");
            });
    }

    @Test
    void usesFirstTagAndReadsDeprecatedFlag() {
        final ApiSpec spec = new OpenApiSpecParser(SPEC).spec();
        final ApiOperation post = op(spec, "POST", "/resources");
        assertThat(post.tag()).isEqualTo("Resources");
        assertThat(post).returns(true, ApiOperation::deprecated);
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
                assertThat(p).returns(true,
                    OpenApiSpecParser.ParameterSpec::required);
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

    @Test
    void parsesOpenApiLocalRefsPathParametersAndRequestBody() {
        final String spec = """
            {
              "openapi": "3.1.0",
              "paths": {
                "/resources": {
                  "parameters": [
                    { "$ref": "#/components/parameters/TraceId" }
                  ],
                  "post": {
                    "requestBody": {
                      "$ref": "#/components/requestBodies/JsonOrder"
                    },
                    "responses": {
                      "201": { "$ref": "#/components/responses/Created" }
                    }
                  }
                }
              },
              "components": {
                "parameters": {
                  "TraceId": {
                    "name": "X-Trace-Id",
                    "in": "header",
                    "required": false
                  }
                },
                "requestBodies": {
                  "JsonOrder": {
                    "content": {
                      "application/json": { "schema": { "type": "object" } }
                    }
                  }
                },
                "responses": {
                  "Created": {
                    "description": "Created",
                    "content": {
                      "application/json": { "schema": { "type": "object" } }
                    }
                  }
                }
              }
            }
            """;
        final ApiOperation post = op(new OpenApiSpecParser(spec).spec(),
            "POST", "/resources");

        assertThat(post).returns(true, ApiOperation::hasDeclaredRequestBody);
        assertThat(post.parameters())
            .anySatisfy(p -> {
                assertThat(p.name()).isEqualTo("X-Trace-Id");
                assertThat(p.in()).isEqualTo("header");
            });
        assertThat(post.mediaTypes()).containsExactly("application/json");
    }

    @Test
    void parsesSwagger20WithRefsPathParametersAndBody() {
        final String spec = """
            {
              "swagger": "2.0",
              "consumes": ["application/json"],
              "produces": ["application/json"],
              "paths": {
                "/items/{id}": {
                  "parameters": [
                    { "name": "id", "in": "path", "required": true,
                      "type": "string" }
                  ],
                  "post": {
                    "tags": ["Items"],
                    "parameters": [
                      { "$ref": "#/parameters/ItemBody" },
                      { "$ref": "#/parameters/Trace" }
                    ],
                    "responses": {
                      "201": { "$ref": "#/responses/Created" }
                    }
                  }
                }
              },
              "parameters": {
                "ItemBody": {
                  "name": "item",
                  "in": "body",
                  "required": true,
                  "schema": { "$ref": "#/definitions/Item" }
                },
                "Trace": {
                  "name": "trace",
                  "in": "query",
                  "required": false,
                  "type": "string"
                }
              },
              "responses": {
                "Created": {
                  "description": "Created",
                  "schema": { "$ref": "#/definitions/Item" }
                }
              },
              "definitions": {
                "Item": { "type": "object" }
              }
            }
            """;
        final ApiSpec parsed = new OpenApiSpecParser(spec).spec();
        final ApiOperation post = op(parsed, "POST", "/items/{id}");

        assertThat(parsed.version()).isEqualTo("2.0");
        assertThat(post.tag()).isEqualTo("Items");
        assertThat(post.statusCodes()).containsExactly(201);
        assertThat(post).returns(true, ApiOperation::hasDeclaredRequestBody);
        assertThat(post.mediaTypes()).containsExactly("application/json");
        assertThat(post.parameters())
            .anySatisfy(p -> {
                assertThat(p.name()).isEqualTo("id");
                assertThat(p.in()).isEqualTo("path");
                assertThat(p).returns(true,
                    OpenApiSpecParser.ParameterSpec::required);
            })
            .anySatisfy(p -> {
                assertThat(p.name()).isEqualTo("item");
                assertThat(p.in()).isEqualTo("body");
                assertThat(p).returns(true,
                    OpenApiSpecParser.ParameterSpec::required);
            })
            .anySatisfy(p -> {
                assertThat(p.name()).isEqualTo("trace");
                assertThat(p.in()).isEqualTo("query");
            });
    }

    @Test
    void rejectsSpecWithoutOpenApiOrSwaggerVersion() {
        assertThatThrownBy(() -> new OpenApiSpecParser("{ \"paths\": {} }"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("openapi")
            .hasMessageContaining("swagger");
    }

    @Test
    void rejectsUnsupportedSwaggerVersion() {
        assertThatThrownBy(() -> new OpenApiSpecParser(
            "{ \"swagger\": \"1.2\", \"paths\": {} }"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported Swagger version")
            .hasMessageContaining("1.2");
    }
}
