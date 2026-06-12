package coverage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads per-request coverage JSON files, matches recorded operations against
 * the OpenAPI spec, and produces a detailed coverage report with per-operation
 * conditions analysis (status codes, parameters, request body).
 */
@Slf4j
public final class CoverageComparator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");

    private final OpenApiSpecParser.ApiSpec spec;
    private final List<RecordedOperation> recordedOps;
    private final Map<String, List<PatternEntry>> specPathPatterns;

    public CoverageComparator(
            final OpenApiSpecParser.ApiSpec spec,
            final List<RecordedOperation> recordedOps) {
        this.spec = spec;
        this.recordedOps = recordedOps;
        this.specPathPatterns = buildPatterns(spec.operations());
    }

    /**
     * Reads coverage JSON files from the given directory.
     */
    public static List<RecordedOperation> readCoverageFiles(
            final String coverageDir) {
        final File dir = new File(coverageDir);
        if (!dir.isDirectory()) {
            log.warn("Coverage output directory not found: {}", coverageDir);
            return Collections.emptyList();
        }

        final File[] files = dir.listFiles(
            (f, name) -> name.endsWith("-coverage.json"));
        if (files == null || files.length == 0) {
            log.warn("No coverage JSON files found in {}", coverageDir);
            return Collections.emptyList();
        }

        log.info("Reading {} coverage files from {}", files.length, coverageDir);
        final List<RecordedOperation> ops = new ArrayList<>();
        for (final File file : files) {
            final RecordedOperation op = parseCoverageFile(file);
            if (op != null) {
                ops.add(op);
            }
        }
        log.info("Parsed {} recorded operations from {} files",
            ops.size(), files.length);
        return ops;
    }

    @SneakyThrows
    static RecordedOperation parseCoverageFile(final File file) {
        try {
            final JsonNode root = MAPPER.readTree(file);
            final JsonNode pathsNode = root.get("paths");
            if (pathsNode == null || pathsNode.isEmpty()) {
                return null;
            }

            final Map.Entry<String, JsonNode> pathEntry
                = pathsNode.properties().iterator().next();
            final String originalPath = pathEntry.getKey();
            final JsonNode methodsNode = pathEntry.getValue();

            final Map.Entry<String, JsonNode> methodEntry
                = methodsNode.properties().iterator().next();
            final String method = methodEntry.getKey().toUpperCase();
            final JsonNode operationNode = methodEntry.getValue();

            final int statusCode = extractStatusCode(operationNode);
            final Set<String> paramNames
                = extractParameterNames(operationNode, false);
            final Set<String> nonEmptyParamNames
                = extractParameterNames(operationNode, true);
            final boolean hasBody = extractHasBody(operationNode);
            final Set<String> mediaTypes = extractMediaTypes(operationNode);

            return new RecordedOperation(
                originalPath,
                method, statusCode, paramNames, nonEmptyParamNames,
                hasBody, mediaTypes);
        } catch (final IOException e) {
            log.warn("Failed to parse coverage file: {} (skipping)",
                file.getName());
            return null;
        }
    }

    /**
     * Runs detailed coverage analysis.
     */
    public DetailedCoverageResult analyze() {
        // Map each recorded request to the spec operation(s) it matches, using
        // templated-path regex matching (e.g. recorded /orders/12345 matches
        // spec /orders/{id}). Exact string equality would miss every
        // path-parameterised endpoint.
        final Map<OpenApiSpecParser.ApiOperation, List<RecordedOperation>> grouped
            = new HashMap<>();
        for (final RecordedOperation op : recordedOps) {
            for (final OpenApiSpecParser.ApiOperation specOp
                    : findMatchingSpecOps(op.originalPath(), op.method())) {
                grouped.computeIfAbsent(specOp, k -> new ArrayList<>()).add(op);
            }
        }

        final List<OperationDetail> details = new ArrayList<>();
        int totalConditionsAll = 0;
        int coveredConditionsAll = 0;
        int responseStatusTotal = 0;
        int responseStatusCovered = 0;
        int paramValueTotal = 0;
        int paramValueCovered = 0;
        int requestBodyTotal = 0;
        int requestBodyCovered = 0;
        int mediaTypeTotal = 0;
        int mediaTypeCovered = 0;

        for (final OpenApiSpecParser.ApiOperation specOp : spec.operations()) {
            final List<RecordedOperation> hits
                = grouped.getOrDefault(specOp, Collections.emptyList());

            final int callCount = hits.size();

            final boolean hasCalls = callCount > 0;

            // --- Status code conditions ---
            final Set<Integer> seenStatuses = new HashSet<>();
            for (final RecordedOperation hit : hits) {
                seenStatuses.add(hit.statusCode());
            }
            final String noCallNote = "No call - no statuses received";
            final List<ConditionResult> conditions = new ArrayList<>();

            for (final int definedCode : specOp.statusCodes()) {
                final boolean covered = seenStatuses.contains(definedCode);
                conditions.add(new ConditionResult(
                    "Response Status",
                    "HTTP status " + definedCode,
                    covered,
                    hasCalls ? "" : noCallNote));
                responseStatusTotal++;
                totalConditionsAll++;
                if (covered) {
                    responseStatusCovered++;
                    coveredConditionsAll++;
                }
            }

            // Undeclared statuses = received but NOT described in the spec.

            final List<Integer> undeclared = new ArrayList<>();
            for (final int seen : seenStatuses) {
                if (!specOp.statusCodes().contains(seen)) {
                    undeclared.add(seen);
                }
            }
            Collections.sort(undeclared);

            // --- Parameter value conditions ---
            final Set<String> seenParams = new HashSet<>();
            final Set<String> seenNonEmptyParams = new HashSet<>();
            for (final RecordedOperation hit : hits) {
                seenParams.addAll(hit.paramNames());
                seenNonEmptyParams.addAll(hit.nonEmptyParamNames());
            }
            for (final OpenApiSpecParser.ParameterSpec param
                    : specOp.parameters()) {
                // Skip path params and always-present headers
                if ("path".equals(param.in())) {
                    continue;
                }
                if ("header".equals(param.in())
                    && List.of("Authorization", "Content-Type", "Accept",
                               "X-Api-Key")
                        .contains(param.name())) {
                    continue;
                }
                // Covered only when the parameter was sent with a non-empty
                // value (true "is not empty" check), not merely present.
                final boolean covered
                    = seenNonEmptyParams.contains(param.name());
                conditions.add(new ConditionResult(
                    "Check parameter value",
                    param.in() + " <" + param.name() + "> is not empty",
                    covered,
                    hasCalls ? "" : noCallNote));
                paramValueTotal++;
                totalConditionsAll++;
                if (covered) {
                    paramValueCovered++;
                    coveredConditionsAll++;
                }
            }

            // --- Request Body Availability ---
            if (BODY_METHODS.contains(specOp.method().toUpperCase())) {
                requestBodyTotal++;
                // Covered when at least one recorded call actually sent a
                // non-empty request body (precise, not approximated).
                final boolean hadBody = hits.stream()
                    .anyMatch(RecordedOperation::hasBody);
                conditions.add(new ConditionResult(
                    "Request Body Availability",
                    "not empty body request",
                    hadBody,
                    hasCalls ? "" : noCallNote));
                totalConditionsAll++;
                if (hadBody) {
                    requestBodyCovered++;
                    coveredConditionsAll++;
                }
            }

            // --- Media Type conditions ---
            final Set<String> seenMediaTypes = new HashSet<>();
            for (final RecordedOperation hit : hits) {
                seenMediaTypes.addAll(hit.mediaTypes());
            }
            for (final String declaredType : specOp.mediaTypes()) {
                final boolean covered
                    = mediaTypeExercised(declaredType, seenMediaTypes);
                conditions.add(new ConditionResult(
                    "Media Type",
                    "content type <" + declaredType + "> exercised",
                    covered,
                    hasCalls ? "" : noCallNote));
                mediaTypeTotal++;
                totalConditionsAll++;
                if (covered) {
                    mediaTypeCovered++;
                    coveredConditionsAll++;
                }
            }

            details.add(new OperationDetail(
                specOp.path(), specOp.method(),
                specOp.tag(), specOp.summary(), specOp.deprecated(),
                callCount, callCount > 0,
                specOp.statusCodes(), seenStatuses,
                undeclared,
                specOp.parameters().stream()
                    .filter(p -> !"path".equals(p.in()))
                    .map(OpenApiSpecParser.ParameterSpec::name).toList(),
                new ArrayList<>(seenParams),
                conditions,
                conditions.isEmpty() ? 100.0
                    : (double) conditions.stream()
                        .filter(ConditionResult::covered).count()
                        / conditions.size() * 100.0));
        }

        // Count operations by coverage level
        int full = 0;
        int partial = 0;
        int empty = 0;
        int noCall = 0;
        int missedRequests = 0;
        int undeclaredOps = 0;
        int deprecatedOps = 0;
        final Map<String, TagAccumulator> tagAcc = new LinkedHashMap<>();
        for (final OperationDetail op : details) {
            if (!op.undeclaredStatuses().isEmpty()) {
                undeclaredOps++;
            }
            if (op.deprecated()) {
                deprecatedOps++;
            }
            final TagAccumulator acc = tagAcc.computeIfAbsent(
                op.tag(), TagAccumulator::new);
            acc.total++;
            acc.totalConditions += op.totalConditions();
            acc.coveredConditions += op.coveredConditions();
            if (op.callCount() == 0) {
                noCall++;
                empty++;
                acc.noCall++;
                acc.empty++;
            } else if (op.coveragePercent() >= 99.9) {
                full++;
                acc.full++;
            } else if (op.coveragePercent() > 0) {
                partial++;
                acc.partial++;
            } else {
                empty++;
                acc.empty++;
            }
            if (op.callCount() > 0) {
                missedRequests += op.conditions().stream()
                    .filter(c -> !c.covered()).count();
            }
        }

        final Map<String, TagSummary> tagSummaries = new LinkedHashMap<>();
        for (final TagAccumulator acc : tagAcc.values()) {
            tagSummaries.put(acc.name, new TagSummary(
                acc.name, acc.total, acc.full, acc.partial,
                acc.empty, acc.noCall,
                acc.coveredConditions, acc.totalConditions));
        }

        final Map<String, ConditionTypeSummary> typeSummaries
            = new LinkedHashMap<>();
        typeSummaries.put("Response Status",
            new ConditionTypeSummary("Response Status",
                "Check that there is an answer with the http status "
                    + "described in responses",
                responseStatusCovered, responseStatusTotal));
        typeSummaries.put("Check parameter value",
            new ConditionTypeSummary("Check parameter value",
                "Check that the parameter is empty or not empty",
                paramValueCovered, paramValueTotal));
        typeSummaries.put("Request Body Availability",
            new ConditionTypeSummary("Request Body Availability",
                "Verify that the request body was not empty",
                requestBodyCovered, requestBodyTotal));
        typeSummaries.put("Media Type",
            new ConditionTypeSummary("Media Type",
                "Check that each declared request/response content type "
                    + "was exercised",
                mediaTypeCovered, mediaTypeTotal));

        return new DetailedCoverageResult(
            details, full, partial, empty, noCall, missedRequests,
            totalConditionsAll, coveredConditionsAll, typeSummaries,
            tagSummaries, undeclaredOps, deprecatedOps);
    }

    private List<OpenApiSpecParser.ApiOperation> findMatchingSpecOps(
            final String recordedPath, final String method) {
        final List<PatternEntry> patterns = specPathPatterns
            .getOrDefault(method.toUpperCase(), Collections.emptyList());

        // Match as-is, then progressively strip leading path segments so any
        // deployment base path is absorbed without environment-specific config.
        String candidate = recordedPath;
        while (true) {
            final List<OpenApiSpecParser.ApiOperation> matches
                = new ArrayList<>();
            for (final PatternEntry entry : patterns) {
                if (entry.pattern().matcher(candidate).matches()) {
                    matches.add(entry.operation());
                }
            }
            if (!matches.isEmpty()) {
                return matches;
            }
            final int nextSlash = candidate.indexOf('/', 1);
            if (nextSlash < 0) {
                return Collections.emptyList();
            }
            candidate = candidate.substring(nextSlash);
        }
    }

    static String pathToRegex(final String path) {
        final String escaped = path
            .replaceAll("\\{([^}]+)}", "[^/]+")
            .replace("/", "\\/");
        return "^" + escaped + "$";
    }

    private static Map<String, List<PatternEntry>> buildPatterns(
            final List<OpenApiSpecParser.ApiOperation> ops) {
        final Map<String, List<PatternEntry>> map = new HashMap<>();
        for (final OpenApiSpecParser.ApiOperation op : ops) {
            final String method = op.method().toUpperCase();
            final Pattern pattern = Pattern.compile(pathToRegex(op.path()));
            map.computeIfAbsent(method, k -> new ArrayList<>())
                .add(new PatternEntry(pattern, op));
        }
        return map;
    }

    private static int extractStatusCode(final JsonNode operationNode) {
        final JsonNode responses = operationNode.get("responses");
        if (responses == null) {
            return 0;
        }
        final Iterator<String> codes = responses.fieldNames();
        if (codes.hasNext()) {
            try {
                return Integer.parseInt(codes.next());
            } catch (final NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Extracts recorded parameter names. When {@code nonEmptyOnly} is true, only
     * names whose recorded {@code value} is present and non-blank are returned —
     * this powers the "parameter value is not empty" coverage condition.
     */
    private static Set<String> extractParameterNames(
            final JsonNode operationNode, final boolean nonEmptyOnly) {
        final JsonNode params = operationNode.get("parameters");
        if (params == null || !params.isArray()) {
            return Collections.emptySet();
        }
        final Set<String> result = new HashSet<>();
        for (final JsonNode param : params) {
            final JsonNode nameNode = param.get("name");
            if (nameNode == null) {
                continue;
            }
            if (nonEmptyOnly) {
                final JsonNode valueNode = param.get("value");
                if (valueNode == null || valueNode.asText().isBlank()) {
                    continue;
                }
            }
            result.add(nameNode.asText());
        }
        return result;
    }

    private static boolean extractHasBody(final JsonNode operationNode) {
        final JsonNode requestBody = operationNode.get("requestBody");
        return requestBody != null
            && requestBody.path("present").asBoolean(false);
    }

    /**
     * Whether a declared media type was exercised, honouring OpenAPI media
     * ranges: {@code *}/{@code *} matches any exercised type, {@code type/*}
     * matches by prefix, otherwise an exact match is required.
     */
    private static boolean mediaTypeExercised(
            final String declared, final Set<String> seen) {
        if ("*/*".equals(declared)) {
            return !seen.isEmpty();
        }
        if (declared.endsWith("/*")) {
            final String prefix = declared.substring(0, declared.length() - 1);
            return seen.stream().anyMatch(type -> type.startsWith(prefix));
        }
        return seen.contains(declared);
    }

    private static Set<String> extractMediaTypes(final JsonNode operationNode) {
        final JsonNode mediaTypes = operationNode.get("mediaTypes");
        if (mediaTypes == null || !mediaTypes.isArray()) {
            return Collections.emptySet();
        }
        final Set<String> result = new HashSet<>();
        for (final JsonNode mediaType : mediaTypes) {
            final String value = mediaType.asText();
            if (!value.isBlank()) {
                result.add(value);
            }
        }
        return result;
    }

    // ----- Data model -----

    /**
     * A recorded HTTP operation from coverage output.
     */
    public record RecordedOperation(
            String originalPath,
            String method,
            int statusCode,
            Set<String> paramNames,
            Set<String> nonEmptyParamNames,
            boolean hasBody,
            Set<String> mediaTypes) {
    }

    /**
     * A single coverage condition result for an operation.
     */
    public record ConditionResult(
            String category,
            String description,
            boolean covered,
            String details) {
    }

    /**
     * Coverage detail for a single spec operation.
     */
    public record OperationDetail(
            String path,
            String method,
            String tag,
            String summary,
            boolean deprecated,
            int callCount,
            boolean hasCalls,
            Set<Integer> definedStatusCodes,
            Set<Integer> seenStatusCodes,
            List<Integer> undeclaredStatuses,
            List<String> definedParameters,
            List<String> seenParameters,
            List<ConditionResult> conditions,
            double coveragePercent) {

        public int totalConditions() {
            return conditions.size();
        }

        public int coveredConditions() {
            return (int) conditions.stream()
                .filter(ConditionResult::covered).count();
        }
    }

    /**
     * Summary for a condition type / category across all operations.
     */
    public record ConditionTypeSummary(
            String name,
            String description,
            int covered,
            int total) {

        public double percent() {
            return total == 0 ? 100.0
                : (double) covered / total * 100.0;
        }
    }

    /**
     * Summary for a single tag (group of operations) across the spec.
     */
    public record TagSummary(
            String name,
            int total,
            int full,
            int partial,
            int empty,
            int noCall,
            int coveredConditions,
            int totalConditions) {

        public double percent() {
            return totalConditions == 0 ? 100.0
                : (double) coveredConditions / totalConditions * 100.0;
        }
    }

    /**
     * Mutable accumulator used while aggregating per-tag statistics.
     */
    private static final class TagAccumulator {
        private final String name;
        private int total;
        private int full;
        private int partial;
        private int empty;
        private int noCall;
        private int coveredConditions;
        private int totalConditions;

        private TagAccumulator(final String name) {
            this.name = name;
        }
    }

    /**
     * Full detailed coverage analysis result.
     */
    public record DetailedCoverageResult(
            List<OperationDetail> operations,
            int fullCoverageCount,
            int partialCoverageCount,
            int emptyCoverageCount,
            int noCallCount,
            int missedRequestCount,
            int totalConditions,
            int coveredConditions,
            Map<String, ConditionTypeSummary> conditionTypes,
            Map<String, TagSummary> tagSummaries,
            int undeclaredOpsCount,
            int deprecatedOpsCount) {

        public int totalOperations() {
            return operations.size();
        }

        public int operationsWithCalls() {
            return (int) operations.stream()
                .filter(OperationDetail::hasCalls).count();
        }

        public double fullCoveragePercent() {
            return totalOperations() == 0 ? 0.0
                : (double) fullCoverageCount / totalOperations() * 100.0;
        }

        public double partialCoveragePercent() {
            return totalOperations() == 0 ? 0.0
                : (double) partialCoverageCount / totalOperations() * 100.0;
        }

        public double emptyCoveragePercent() {
            return totalOperations() == 0 ? 0.0
                : (double) emptyCoverageCount / totalOperations() * 100.0;
        }

        public double totalCoveredPercent() {
            return totalConditions == 0 ? 0.0
                : (double) coveredConditions / totalConditions * 100.0;
        }
    }

    private record PatternEntry(
            Pattern pattern,
            OpenApiSpecParser.ApiOperation operation) {
    }
}
