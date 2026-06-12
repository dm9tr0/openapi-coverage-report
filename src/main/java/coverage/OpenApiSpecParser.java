package coverage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.SneakyThrows;

/**
 * Parses an OpenAPI 3.x specification (JSON) using Jackson.
 * Extracts paths, HTTP methods, status codes, and parameters.
 * Supports both 3.0.x and 3.1.x formats.
 */
public final class OpenApiSpecParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> HTTP_METHODS = List.of(
        "get", "post", "put", "patch", "delete", "head", "options"
    );

    private final ApiSpec spec;

    @SneakyThrows
    public OpenApiSpecParser(final String jsonContent) {
        final JsonNode root = MAPPER.readTree(jsonContent);
        final String version = root.get("openapi").asText();
        final List<ApiOperation> operations = parsePaths(root.get("paths"));
        this.spec = new ApiSpec(version, Collections.unmodifiableList(operations));
    }

    public ApiSpec spec() {
        return spec;
    }

    private List<ApiOperation> parsePaths(final JsonNode pathsNode) {
        final List<ApiOperation> result = new ArrayList<>();
        if (pathsNode == null) {
            return result;
        }

        final Iterator<Map.Entry<String, JsonNode>> pathFields =
            pathsNode.properties().iterator();
        while (pathFields.hasNext()) {
            final Map.Entry<String, JsonNode> pathEntry = pathFields.next();
            final String path = pathEntry.getKey();
            final JsonNode methodsNode = pathEntry.getValue();

            for (final String method : HTTP_METHODS) {
                final JsonNode operationNode = methodsNode.get(method);
                if (operationNode == null) {
                    continue;
                }

                final Set<Integer> statusCodes = parseStatusCodes(
                    operationNode.get("responses"));
                final List<ParameterSpec> parameters = parseParameters(
                    operationNode, path);

                final boolean deprecated = operationNode.has("deprecated")
                    && operationNode.get("deprecated").asBoolean();
                result.add(new ApiOperation(
                    path, method.toUpperCase(),
                    statusCodes, parameters,
                    parseTag(operationNode), parseSummary(operationNode),
                    deprecated, parseMediaTypes(operationNode)));
            }
        }
        return result;
    }

    private Set<Integer> parseStatusCodes(final JsonNode responsesNode) {
        if (responsesNode == null) {
            return Collections.emptySet();
        }

        final Set<Integer> codes = new LinkedHashSet<>();
        final Iterator<String> fieldNames = responsesNode.fieldNames();
        while (fieldNames.hasNext()) {
            final String code = fieldNames.next();
            if ("default".equals(code)) {
                continue;
            }
            try {
                codes.add(Integer.parseInt(code));
            } catch (final NumberFormatException ignored) {
                // Skip non-numeric keys like "default"
            }
        }
        return codes;
    }

    private List<ParameterSpec> parseParameters(
            final JsonNode operationNode, final String path) {
        final List<ParameterSpec> params = new ArrayList<>();

        // Parse inline parameters
        final JsonNode paramsNode = operationNode.get("parameters");
        if (paramsNode != null && paramsNode.isArray()) {
            for (final JsonNode paramNode : paramsNode) {
                final JsonNode nameNode = paramNode.get("name");
                final JsonNode inNode = paramNode.get("in");
                if (nameNode == null || inNode == null) {
                    continue;
                }
                final boolean required = paramNode.has("required")
                    && paramNode.get("required").asBoolean();
                params.add(new ParameterSpec(
                    nameNode.asText(), inNode.asText(), required));
            }
        }

        // Extract path parameters from path template if not declared
        final List<String> pathParams = extractPathTemplateParams(path);
        for (final String paramName : pathParams) {
            final boolean alreadyDeclared = params.stream()
                .anyMatch(p -> p.name().equals(paramName)
                    && "path".equals(p.in()));
            if (!alreadyDeclared) {
                params.add(new ParameterSpec(paramName, "path", true));
            }
        }

        return params;
    }

    /**
     * Extracts the grouping tag for an operation (first declared tag),
     * defaulting to {@code "Untagged"} when none is present.
     */
    private String parseTag(final JsonNode operationNode) {
        final JsonNode tagsNode = operationNode.get("tags");
        if (tagsNode != null && tagsNode.isArray() && tagsNode.size() > 0) {
            final String first = tagsNode.get(0).asText();
            if (first != null && !first.isBlank()) {
                return first;
            }
        }
        return "Untagged";
    }

    /**
     * Extracts the human-readable summary for an operation, or an empty
     * string when the spec does not declare one.
     */
    private String parseSummary(final JsonNode operationNode) {
        final JsonNode summaryNode = operationNode.get("summary");
        return summaryNode == null ? "" : summaryNode.asText("");
    }

    /**
     * Collects the declared media types for an operation — the union of request
     * body content types and all response content types (OAS 3.x
     * {@code content} maps). Powers the "Media Type" coverage condition.
     */
    private Set<String> parseMediaTypes(final JsonNode operationNode) {
        final Set<String> mediaTypes = new LinkedHashSet<>();
        final JsonNode requestBody = operationNode.get("requestBody");
        if (requestBody != null) {
            collectContentKeys(requestBody.get("content"), mediaTypes);
        }
        final JsonNode responses = operationNode.get("responses");
        if (responses != null) {
            for (final JsonNode responseNode : responses) {
                collectContentKeys(responseNode.get("content"), mediaTypes);
            }
        }
        return mediaTypes;
    }

    private static void collectContentKeys(
            final JsonNode contentNode, final Set<String> out) {
        if (contentNode == null || !contentNode.isObject()) {
            return;
        }
        final Iterator<String> names = contentNode.fieldNames();
        while (names.hasNext()) {
            out.add(names.next().toLowerCase(Locale.ROOT).trim());
        }
    }

    static List<String> extractPathTemplateParams(final String path) {
        final List<String> params = new ArrayList<>();
        int start = path.indexOf('{');
        while (start != -1) {
            final int end = path.indexOf('}', start);
            if (end == -1) {
                break;
            }
            params.add(path.substring(start + 1, end));
            start = path.indexOf('{', end + 1);
        }
        return params;
    }

    /**
     * Data model for a parsed OpenAPI specification.
     *
     * @param version    the OpenAPI version string (e.g. "3.1.0")
     * @param operations the list of parsed API operations
     */
    public record ApiSpec(String version, List<ApiOperation> operations) {
    }

    /**
     * Data model for a single API operation in the spec.
     *
     * @param path       the operation path (e.g. "/orders/{id}")
     * @param method     the HTTP method (GET, POST, PUT, etc.)
     * @param statusCodes the HTTP status codes defined for this operation
     * @param parameters the operation parameters
     * @param tag        the grouping tag (first declared tag, or "Untagged")
     * @param summary    the human-readable operation summary (may be empty)
     * @param deprecated whether the operation is marked deprecated in the spec
     * @param mediaTypes the declared request/response content types
     */
    public record ApiOperation(
            String path, String method,
            Set<Integer> statusCodes,
            List<ParameterSpec> parameters,
            String tag, String summary,
            boolean deprecated,
            Set<String> mediaTypes) {
    }

    /**
     * Data model for a parameter definition in the spec.
     *
     * @param name     the parameter name
     * @param in       the parameter location (path, query, header)
     * @param required whether the parameter is required
     */
    public record ParameterSpec(
            String name, String in, boolean required) {
    }
}
