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
        "get", "post", "put", "patch", "delete", "head", "options", "trace"
    );

    private final JsonNode root;
    private final boolean swagger2;
    private final ApiSpec spec;

    @SneakyThrows
    public OpenApiSpecParser(final String jsonContent) {
        this.root = MAPPER.readTree(jsonContent);
        final String version = detectVersion(root);
        this.swagger2 = root.hasNonNull("swagger");
        final List<ApiOperation> operations = parsePaths(root.get("paths"));
        this.spec = new ApiSpec(version, Collections.unmodifiableList(operations));
    }

    public ApiSpec spec() {
        return spec;
    }

    private static String detectVersion(final JsonNode root) {
        if (root.hasNonNull("openapi")) {
            return root.get("openapi").asText();
        }
        if (root.hasNonNull("swagger")) {
            final String version = root.get("swagger").asText();
            if (!"2.0".equals(version)) {
                throw new IllegalArgumentException(
                    "Unsupported Swagger version: " + version);
            }
            return version;
        }
        throw new IllegalArgumentException(
            "Spec must declare an openapi or swagger version.");
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
                    methodsNode, operationNode, path);

                final boolean deprecated = operationNode.has("deprecated")
                    && operationNode.get("deprecated").asBoolean();
                result.add(new ApiOperation(
                    path, method.toUpperCase(),
                    statusCodes, parameters,
                    parseTag(operationNode), parseSummary(operationNode),
                    deprecated, parseMediaTypes(methodsNode, operationNode),
                    hasDeclaredRequestBody(methodsNode, operationNode)));
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
            final JsonNode pathNode, final JsonNode operationNode,
            final String path) {
        final List<ParameterSpec> params = new ArrayList<>();

        addParameters(pathNode.get("parameters"), params);
        addParameters(operationNode.get("parameters"), params);

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

    private void addParameters(final JsonNode paramsNode,
            final List<ParameterSpec> params) {
        if (paramsNode != null && paramsNode.isArray()) {
            for (final JsonNode rawParamNode : paramsNode) {
                final JsonNode paramNode = resolveRef(rawParamNode);
                if (paramNode == null) {
                    continue;
                }
                final JsonNode nameNode = paramNode.get("name");
                final JsonNode inNode = paramNode.get("in");
                if (nameNode == null || inNode == null) {
                    continue;
                }
                final boolean required = paramNode.has("required")
                    && paramNode.get("required").asBoolean();
                final String name = nameNode.asText();
                final String in = inNode.asText();
                params.removeIf(p -> p.name().equals(name) && p.in().equals(in));
                params.add(new ParameterSpec(name, in, required));
            }
        }
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
    private Set<String> parseMediaTypes(
            final JsonNode pathNode, final JsonNode operationNode) {
        final Set<String> mediaTypes = new LinkedHashSet<>();
        if (swagger2) {
            collectStringArray(firstPresent(
                operationNode.get("consumes"), pathNode.get("consumes"),
                root.get("consumes")), mediaTypes);
            collectStringArray(firstPresent(
                operationNode.get("produces"), pathNode.get("produces"),
                root.get("produces")), mediaTypes);
            return mediaTypes;
        }
        final JsonNode requestBody = operationNode.get("requestBody");
        if (requestBody != null) {
            final JsonNode resolved = resolveRef(requestBody);
            if (resolved != null) {
                collectContentKeys(resolved.get("content"), mediaTypes);
            }
        }
        final JsonNode responses = operationNode.get("responses");
        if (responses != null) {
            for (final JsonNode rawResponseNode : responses) {
                final JsonNode responseNode = resolveRef(rawResponseNode);
                if (responseNode != null) {
                    collectContentKeys(responseNode.get("content"), mediaTypes);
                }
            }
        }
        return mediaTypes;
    }

    private boolean hasDeclaredRequestBody(
            final JsonNode pathNode, final JsonNode operationNode) {
        if (!swagger2) {
            return operationNode.has("requestBody")
                && resolveRef(operationNode.get("requestBody")) != null;
        }
        return hasBodyParameter(pathNode.get("parameters"))
            || hasBodyParameter(operationNode.get("parameters"));
    }

    private boolean hasBodyParameter(final JsonNode paramsNode) {
        if (paramsNode == null || !paramsNode.isArray()) {
            return false;
        }
        for (final JsonNode rawParamNode : paramsNode) {
            final JsonNode paramNode = resolveRef(rawParamNode);
            if (paramNode == null) {
                continue;
            }
            final String in = paramNode.path("in").asText();
            if ("body".equals(in) || "formData".equals(in)) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode firstPresent(final JsonNode... nodes) {
        for (final JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull()) {
                return node;
            }
        }
        return null;
    }

    private void collectStringArray(final JsonNode values,
            final Set<String> out) {
        if (values == null || !values.isArray()) {
            return;
        }
        for (final JsonNode value : values) {
            final String text = value.asText("").toLowerCase(Locale.ROOT).trim();
            if (!text.isBlank()) {
                out.add(text);
            }
        }
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

    private JsonNode resolveRef(final JsonNode node) {
        JsonNode current = node;
        for (int depth = 0; depth < 20; depth++) {
            if (current == null || !current.hasNonNull("$ref")) {
                return current;
            }
            final String ref = current.get("$ref").asText();
            if (!ref.startsWith("#/")) {
                return null;
            }
            current = resolveLocalPointer(ref.substring(2));
        }
        throw new IllegalArgumentException("Reference chain is too deep.");
    }

    private JsonNode resolveLocalPointer(final String pointer) {
        JsonNode current = root;
        for (final String rawPart : pointer.split("/")) {
            final String part = rawPart.replace("~1", "/").replace("~0", "~");
            current = current.get(part);
            if (current == null) {
                return null;
            }
        }
        return current;
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
     * Parsed OpenAPI specification record.
     *
     * @param version    the OpenAPI version string (e.g. "3.1.0")
     * @param operations the list of parsed API operations
     */
    public record ApiSpec(String version, List<ApiOperation> operations) {
    }

    /**
     * Parsed API operation record.
     *
     * @param path       the operation path (e.g. "/resources/{id}")
     * @param method     the HTTP method (GET, POST, PUT, etc.)
     * @param statusCodes the HTTP status codes defined for this operation
     * @param parameters the operation parameters
     * @param tag        the grouping tag (first declared tag, or "Untagged")
     * @param summary    the human-readable operation summary (may be empty)
     * @param deprecated whether the operation is marked deprecated in the spec
     * @param mediaTypes the declared request/response content types
     * @param hasDeclaredRequestBody whether the spec declares a request body
     */
    public record ApiOperation(
            String path, String method,
            Set<Integer> statusCodes,
            List<ParameterSpec> parameters,
            String tag, String summary,
            boolean deprecated,
            Set<String> mediaTypes,
            boolean hasDeclaredRequestBody) {
    }

    /**
     * Parsed parameter definition record.
     *
     * @param name     the parameter name
     * @param in       the parameter location (path, query, header)
     * @param required whether the parameter is required
     */
    public record ParameterSpec(
            String name, String in, boolean required) {
    }
}
