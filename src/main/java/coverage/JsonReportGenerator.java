package coverage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import coverage.CoverageComparator.ConditionResult;
import coverage.CoverageComparator.ConditionTypeSummary;
import coverage.CoverageComparator.DetailedCoverageResult;
import coverage.CoverageComparator.OperationDetail;
import coverage.CoverageComparator.TagSummary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Writes the coverage result as a machine-readable {@code coverage-report.json}
 * next to the HTML report. The shape is an explicit, stable contract for CI
 * pipelines (metrics, gates, trend tracking) — it intentionally does NOT
 * serialize the internal {@link DetailedCoverageResult} record directly, so the
 * JSON does not drift with internal refactors and includes derived metrics
 * (percentages) that record accessors would otherwise omit.
 */
@Slf4j
public final class JsonReportGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DetailedCoverageResult result;
    private final String specVersion;
    private final String specSource;
    private final int recordedOperations;

    /**
     * @param result             the analysed coverage result
     * @param specVersion        OpenAPI version of the parsed spec
     * @param specSource         where the spec came from (URL or fallback)
     * @param recordedOperations number of recorded operations parsed
     */
    public JsonReportGenerator(
            final DetailedCoverageResult result,
            final String specVersion,
            final String specSource,
            final int recordedOperations) {
        this.result = result;
        this.specVersion = specVersion;
        this.specSource = specSource;
        this.recordedOperations = recordedOperations;
    }

    /**
     * Writes {@code coverage-report.json} into the given output directory.
     *
     * @param outputDir directory for the JSON report
     */
    public void generate(final String outputDir) {
        final ObjectNode root = MAPPER.createObjectNode();

        final ObjectNode meta = root.putObject("meta");
        meta.put("specVersion", specVersion);
        meta.put("specSource", specSource);
        meta.put("recordedOperations", recordedOperations);

        final ObjectNode summary = root.putObject("summary");
        summary.put("coveredConditions", result.coveredConditions());
        summary.put("totalConditions", result.totalConditions());
        summary.put("coveredPercent", round(result.totalCoveredPercent()));
        summary.put("totalOperations", result.totalOperations());
        summary.put("operationsWithCalls", result.operationsWithCalls());
        summary.put("fullCoverageCount", result.fullCoverageCount());
        summary.put("partialCoverageCount", result.partialCoverageCount());
        summary.put("emptyCoverageCount", result.emptyCoverageCount());
        summary.put("noCallCount", result.noCallCount());
        summary.put("missedRequestCount", result.missedRequestCount());
        summary.put("undeclaredOpsCount", result.undeclaredOpsCount());
        summary.put("deprecatedOpsCount", result.deprecatedOpsCount());

        writeConditionTypes(root.putObject("conditionTypes"),
            result.conditionTypes());
        writeTags(root.putObject("tags"), result.tagSummaries());
        writeOperations(root.putArray("operations"));

        final Path outputFile = Path.of(outputDir, "coverage-report.json");
        try {
            Files.createDirectories(outputFile.getParent());
            Files.writeString(outputFile,
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            log.info("Coverage JSON written to {}", outputFile);
        } catch (final IOException e) {
            log.warn("Failed to write JSON report to {}: {}",
                outputFile, e.getMessage());
        }
    }

    private void writeConditionTypes(final ObjectNode node,
            final Map<String, ConditionTypeSummary> types) {
        types.forEach((key, t) -> {
            final ObjectNode n = node.putObject(key);
            n.put("description", t.description());
            n.put("covered", t.covered());
            n.put("total", t.total());
            n.put("percent", round(t.percent()));
        });
    }

    private void writeTags(final ObjectNode node,
            final Map<String, TagSummary> tags) {
        tags.forEach((key, t) -> {
            final ObjectNode n = node.putObject(key);
            n.put("total", t.total());
            n.put("full", t.full());
            n.put("partial", t.partial());
            n.put("empty", t.empty());
            n.put("noCall", t.noCall());
            n.put("coveredConditions", t.coveredConditions());
            n.put("totalConditions", t.totalConditions());
            n.put("percent", round(t.percent()));
        });
    }

    private void writeOperations(final ArrayNode operations) {
        for (final OperationDetail op : result.operations()) {
            final ObjectNode n = operations.addObject();
            n.put("path", op.path());
            n.put("method", op.method());
            n.put("tag", op.tag());
            n.put("deprecated", op.deprecated());
            n.put("callCount", op.callCount());
            n.put("hasCalls", op.hasCalls());
            n.put("coveragePercent", round(op.coveragePercent()));
            n.put("coveredConditions", op.coveredConditions());
            n.put("totalConditions", op.totalConditions());
            op.definedStatusCodes()
                .forEach(n.putArray("definedStatusCodes")::add);
            op.seenStatusCodes().forEach(n.putArray("seenStatusCodes")::add);
            op.undeclaredStatuses()
                .forEach(n.putArray("undeclaredStatuses")::add);
            final ArrayNode conditions = n.putArray("conditions");
            for (final ConditionResult c : op.conditions()) {
                final ObjectNode cn = conditions.addObject();
                cn.put("category", c.category());
                cn.put("description", c.description());
                cn.put("covered", c.covered());
                cn.put("details", c.details());
            }
        }
    }

    private static double round(final double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
