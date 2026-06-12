package coverage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * Generates an interactive HTML coverage report with SVG pie chart,
 * clickable summary cards, a per-tag coverage breakdown, a searchable,
 * sortable, filterable operation table, and per-operation drill-down
 * (status codes, parameters, and conditions). The report auto-switches
 * between light and dark themes based on the browser/OS preference.
 */
@Slf4j
public final class HtmlReportGenerator {

    private final CoverageComparator.DetailedCoverageResult result;
    private final String specVersion;
    private final String specSource;
    private final int recordedOpsCount;

    public HtmlReportGenerator(
            final CoverageComparator.DetailedCoverageResult result,
            final String specVersion,
            final String specSource,
            final int recordedOpsCount) {
        this.result = result;
        this.specVersion = specVersion;
        this.specSource = specSource;
        this.recordedOpsCount = recordedOpsCount;
    }

    @SneakyThrows
    public void generate(final String outputPath) {
        final String html = buildHtml();
        final Path outputFile = Path.of(outputPath, "coverage-report.html");
        Files.createDirectories(outputFile.getParent());
        Files.writeString(outputFile, html, StandardCharsets.UTF_8);
        log.info("Coverage report written to {}", outputFile.toAbsolutePath());
    }

    private String buildHtml() {
        final double fullPct = result.fullCoveragePercent();
        final double partialPct = result.partialCoveragePercent();
        final double emptyPct = result.emptyCoveragePercent();
        final double condPct = result.totalCoveredPercent();
        final int totalOps = result.totalOperations();
        final int noCallOps = result.noCallCount();
        final int missedReq = result.missedRequestCount();
        final int condTotal = result.totalConditions();
        final int condCovered = result.coveredConditions();

        String t = TEMPLATE;
        t = t.replace("{{SPEC_SOURCE}}", esc(specSource));
        t = t.replace("{{SPEC_VERSION}}", esc(specVersion));
        t = t.replace("{{FULL_PCT}}", fmt1(fullPct));
        t = t.replace("{{PARTIAL_PCT}}", fmt1(partialPct));
        t = t.replace("{{EMPTY_PCT}}", fmt1(emptyPct));
        t = t.replace("{{ALL_OPS}}", String.valueOf(totalOps));
        t = t.replace("{{NO_CALL_OPS}}", String.valueOf(noCallOps));
        t = t.replace("{{MISSED_REQ}}", String.valueOf(missedReq));
        t = t.replace("{{UNDECLARED_OPS}}",
            String.valueOf(result.undeclaredOpsCount()));
        t = t.replace("{{COND_TOTAL}}", String.valueOf(condTotal));
        t = t.replace("{{COND_COVERED}}", String.valueOf(condCovered));
        t = t.replace("{{COND_PCT}}", fmt1(condPct));
        t = t.replace("{{COND_UNCOVERED}}",
            String.valueOf(condTotal - condCovered));
        t = t.replace("{{OVERALL_BAR_CSS}}", barColorClass(condPct));
        t = t.replace("{{FULL_PCT_CSS}}", pctColorClass(fullPct));
        t = t.replace("{{PARTIAL_PCT_CSS}}", pctColorClass(partialPct));
        t = t.replace("{{EMPTY_PCT_CSS}}", pctColorClass(emptyPct));
        t = t.replace("{{COND_PCT_CSS}}", pctColorClass(condPct));

        t = t.replace("{{CONDITION_TYPES}}", buildConditionTypes());
        t = t.replace("{{TAG_CARDS}}", buildTagCards());
        t = t.replace("{{TAG_COUNT}}",
            String.valueOf(result.tagSummaries().size()));
        t = t.replace("{{OPS_TOTAL}}", String.valueOf(totalOps));
        t = t.replace("{{FULL_COUNT}}",
            String.valueOf(result.fullCoverageCount()));
        t = t.replace("{{PARTIAL_COUNT}}",
            String.valueOf(result.partialCoverageCount()));
        t = t.replace("{{EMPTY_COUNT}}",
            String.valueOf(result.emptyCoverageCount()));
        t = t.replace("{{OPS_WITHOUT_CALLS}}",
            String.valueOf(noCallOps));
        t = t.replace("{{MISSED_REQUESTS}}",
            String.valueOf(missedReq));
        t = t.replace("{{DEPRECATED_COUNT}}",
            String.valueOf(result.deprecatedOpsCount()));
        t = t.replace("{{OPS_ROWS}}", buildOperationRows());
        t = t.replace("{{EMPTY_BANNER}}",
            recordedOpsCount == 0 ? EMPTY_BANNER : "");
        t = t.replace("{{GEN_INFO}}", buildGenInfo());
        return t;
    }

    private static final String EMPTY_BANNER =
        "<div class=\"empty-banner\">⚠ No recorded test data was found "
        + "— the report shows 0% coverage. Run your API test suite "
        + "first to generate coverage data.</div>\n";

    private String buildGenInfo() {
        final String now = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        int matchedCalls = 0;
        for (final CoverageComparator.OperationDetail op
                : result.operations()) {
            matchedCalls += op.callCount();
        }
        return "  <div class=\"gen-info\">\n"
            + "    <div><span class=\"gi-k\">Spec source</span>"
            + "<span class=\"gi-v\">" + esc(specSource) + "</span></div>\n"
            + "    <div><span class=\"gi-k\">Spec version</span>"
            + "<span class=\"gi-v\">" + esc(specVersion) + "</span></div>\n"
            + "    <div><span class=\"gi-k\">Recorded requests parsed</span>"
            + "<span class=\"gi-v\">" + recordedOpsCount + "</span></div>\n"
            + "    <div><span class=\"gi-k\">Requests matched to spec</span>"
            + "<span class=\"gi-v\">" + matchedCalls + "</span></div>\n"
            + "    <div><span class=\"gi-k\">Operations in spec</span>"
            + "<span class=\"gi-v\">" + result.totalOperations()
            + "</span></div>\n"
            + "    <div><span class=\"gi-k\">Deprecated operations</span>"
            + "<span class=\"gi-v\">" + result.deprecatedOpsCount()
            + "</span></div>\n"
            + "    <div><span class=\"gi-k\">Generated at</span>"
            + "<span class=\"gi-v\">" + now + "</span></div>\n"
            + "  </div>\n";
    }


    private String buildConditionTypes() {
        final StringBuilder sb = new StringBuilder();
        for (final CoverageComparator.ConditionTypeSummary ct
                : result.conditionTypes().values()) {
            final double pct = ct.percent();
            final String cls = pctColorClass(pct);
            sb.append(
"  <div class=\"cond-type-card clickable\" data-type=\"" + esc(ct.name())
+ "\" title=\"Click to show operations missing this condition\">\n"
+ "    <div class=\"cond-type-header\">\n"
+ "      <div>\n"
+ "        <div class=\"cond-type-name\">" + esc(ct.name()) + "</div>\n"
+ "        <div class=\"cond-type-desc\">" + esc(ct.description())
+ "</div>\n"
+ "      </div>\n"
+ "      <div class=\"cond-type-stat\">"
+ "<span class=\"num " + cls + "\">" + fmt1(pct)
+ "%</span> (" + ct.covered() + "/" + ct.total() + ")</div>\n"
+ "    </div>\n"
+ "    <div class=\"progress-bar " + barColorClass(pct) + "\">\n"
+ "      <div class=\"progress-fill\" style=\"width:"
+ (int) Math.round(pct) + "%\"></div>\n"
+ "    </div>\n"
+ "  </div>\n");
        }
        return sb.toString();
    }

    private String buildTagCards() {
        final StringBuilder sb = new StringBuilder();
        for (final CoverageComparator.TagSummary tag
                : result.tagSummaries().values()) {
            final double pct = tag.percent();
            final String cls = pctColorClass(pct);
            sb.append(
"  <div class=\"tag-card\" data-tag=\"" + esc(tag.name())
+ "\" title=\"Click to filter operations tagged " + esc(tag.name())
+ "\">\n"
+ "    <div class=\"tag-card-head\">\n"
+ "      <span class=\"tag-card-name\">" + esc(tag.name()) + "</span>\n"
+ "      <span class=\"num " + cls + "\">" + fmt1(pct) + "%</span>\n"
+ "    </div>\n"
+ "    <div class=\"progress-bar " + barColorClass(pct) + "\">\n"
+ "      <div class=\"progress-fill\" style=\"width:"
+ (int) Math.round(pct) + "%\"></div>\n"
+ "    </div>\n"
+ "    <div class=\"tag-card-stats\">\n"
+ "      <span class=\"green\">● " + tag.full() + " full</span>\n"
+ "      <span class=\"orange\">● " + tag.partial() + " partial</span>\n"
+ "      <span class=\"red\">● " + tag.empty() + " empty</span>\n"
+ "      <span class=\"muted\">" + tag.total() + " ops</span>\n"
+ "    </div>\n"
+ "  </div>\n");
        }
        return sb.toString();
    }

    private String buildOperationRows() {
        final StringBuilder sb = new StringBuilder();
        // Default order: worst coverage first so gaps surface at the top.
        final List<CoverageComparator.OperationDetail> ops =
            new ArrayList<>(result.operations());
        ops.sort(Comparator
            .comparingDouble(CoverageComparator.OperationDetail::coveragePercent)
            .thenComparing(CoverageComparator.OperationDetail::path));
        int idx = 0;
        for (final CoverageComparator.OperationDetail op : ops) {
            final String method = op.method();
            final String badge = coverageBadge(op);
            final String label = coverageLabel(op);
            final String pctStr = op.hasCalls()
                ? fmt1(op.coveragePercent()) + "%" : "—";
            final String pctCss = op.hasCalls()
                ? pctColorClass(op.coveragePercent()) : "";
            final String condStr = op.hasCalls()
                ? op.coveredConditions() + "/" + op.totalConditions()
                : "—";
            final int barW = op.hasCalls()
                ? (int) Math.round(op.coveragePercent()) : 0;
            final String covSlug = coverageSlug(label);
            final String summaryHtml = op.summary() == null
                || op.summary().isBlank()
                ? ""
                : "<div class=\"op-summary\">" + esc(op.summary()) + "</div>";
            final boolean hasUndeclared = !op.undeclaredStatuses().isEmpty();
            final String undeclaredStr = joinInts(op.undeclaredStatuses());
            final String undeclaredBadge = hasUndeclared
                ? "        <span class=\"badge badge-undeclared\" title=\""
                    + "Returned status codes not described in the spec\">"
                    + "&#9888; " + esc(undeclaredStr) + "</span>\n"
                : "";
            final Set<String> missedCats = new LinkedHashSet<>();
            for (final CoverageComparator.ConditionResult c
                    : op.conditions()) {
                if (!c.covered()) {
                    missedCats.add(c.category());
                }
            }
            final String missedAttr = missedCats.isEmpty()
                ? "" : "||" + String.join("||", missedCats) + "||";
            final String deprecatedPill = op.deprecated()
                ? " <span class=\"badge badge-deprecated\" "
                    + "title=\"Marked deprecated in the spec\">deprecated</span>"
                : "";
            final String deprecatedSearch = op.deprecated() ? " deprecated" : "";

            sb.append(
"    <tr class=\"op-row\" data-cov=\"" + covSlug + "\" data-tag=\""
+ esc(op.tag()) + "\" data-undeclared=\"" + esc(undeclaredStr)
+ "\" data-missed=\"" + esc(missedAttr)
+ "\" data-search=\"" + esc((method + " " + op.path()
+ " " + op.tag() + " " + op.summary() + deprecatedSearch).toLowerCase())
+ "\" onclick=\"toggleOp(" + idx + ")\">\n"
+ "      <td><span class=\"method-tag " + method + "\">" + method
+ "</span></td>\n"
+ "      <td><code>" + esc(op.path()) + "</code>" + deprecatedPill
+ summaryHtml + "</td>\n"
+ "      <td><span class=\"tag-pill\">" + esc(op.tag()) + "</span></td>\n"
+ "      <td>"
+ "<span class=\"badge " + badge + "\">" + label + "</span>\n"
+ undeclaredBadge);
            if (op.hasCalls()) {
                sb.append(
"        <div class=\"progress-bar " + barColorClass(op.coveragePercent())
+ "\">\n"
+ "          <div class=\"progress-fill\" style=\"width:"
+ barW + "%\"></div>\n"
+ "        </div>\n");
            }
            sb.append(
"        <span class=\"" + pctCss + "\">" + pctStr + "</span>\n"
+ "      </td>\n"
+ "      <td>" + op.callCount() + "</td>\n"
+ "      <td>" + condStr + "</td>\n"
+ "    </tr>\n"
+ "    <tr class=\"op-details\" id=\"op-detail-" + idx + "\">\n"
+ "      <td colspan=\"6\">\n"
+ "        <div class=\"op-details-inner\">\n"
+ buildOpStatusCodes(op)
+ buildOpUndeclared(op)
+ buildOpParameters(op)
+ buildOpConditions(op)
+ "        </div>\n"
+ "      </td>\n"
+ "    </tr>\n");
            idx++;
        }
        return sb.toString();
    }

    private String buildOpStatusCodes(
            final CoverageComparator.OperationDetail op) {
        final StringBuilder sb = new StringBuilder();
        sb.append("<h4>Status codes</h4>\n");
        sb.append("<div class=\"status-codes\">\n");
        for (final int code : op.definedStatusCodes()) {
            final boolean seen = op.seenStatusCodes().contains(code);
            sb.append("  <span class=\"status-code "
                + (seen ? "status-seen" : "status-missed") + "\">"
                + code + (seen ? " ✓" : " ✗") + "</span>\n");
        }
        sb.append("</div>\n");
        return sb.toString();
    }

    private String buildOpUndeclared(
            final CoverageComparator.OperationDetail op) {
        if (op.undeclaredStatuses().isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("<h4 class=\"undeclared-h\">Undeclared statuses "
            + "(returned but not in spec)</h4>\n");
        sb.append("<div class=\"status-codes\">\n");
        for (final int code : op.undeclaredStatuses()) {
            sb.append("  <span class=\"status-code status-undeclared\">"
                + code + " &#9888;</span>\n");
        }
        sb.append("</div>\n");
        return sb.toString();
    }

    private String buildOpParameters(
            final CoverageComparator.OperationDetail op) {
        if (op.definedParameters().isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("<h4>Parameters</h4>\n");
        sb.append("<div class=\"status-codes\">\n");
        for (final String param : op.definedParameters()) {
            final boolean seen = op.seenParameters().contains(param);
            sb.append("  <span class=\"status-code "
                + (seen ? "status-seen" : "status-missed") + "\">"
                + esc(param) + (seen ? " ✓" : " ✗") + "</span>\n");
        }
        sb.append("</div>\n");
        return sb.toString();
    }

    private String buildOpConditions(
            final CoverageComparator.OperationDetail op) {
        if (op.conditions().isEmpty()) {
            return "";
        }
        final int covered = op.coveredConditions();
        final int total = op.totalConditions();
        final double pct = total == 0 ? 0.0 : (double) covered / total * 100.0;
        final StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"cond-head\">\n"
            + "  <h4>Conditions</h4>\n"
            + "  <span class=\"cond-cov-label " + pctColorClass(pct) + "\">"
            + covered + "/" + total + " (" + fmt1(pct)
            + "%) conditions covered</span>\n"
            + "</div>\n");
        sb.append("<div class=\"progress-bar " + barColorClass(pct)
            + " cond-cov-bar\">\n"
            + "  <div class=\"progress-fill\" style=\"width:"
            + (int) Math.round(pct) + "%\"></div>\n"
            + "</div>\n");
        sb.append("<table class=\"cond-table\">\n");
        sb.append("<thead><tr><th>Condition name</th>"
            + "<th>Details</th></tr></thead>\n");
        sb.append("<tbody>\n");
        for (final CoverageComparator.ConditionResult c : op.conditions()) {
            final String icon = c.covered() ? "✓" : "✗";
            final String rowCss = c.covered()
                ? "cond-row-covered" : "cond-row-uncovered";
            final String det = c.details() == null ? "" : c.details();
            sb.append("<tr class=\"cond-row " + rowCss + "\">"
                + "<td><span class=\"cond-icon\">" + icon + "</span> "
                + esc(c.description()) + "</td>"
                + "<td class=\"cond-det\">" + esc(det)
                + "</td></tr>\n");
        }
        sb.append("</tbody>\n</table>\n");
        return sb.toString();
    }

    private static String coverageBadge(
            final CoverageComparator.OperationDetail op) {
        if (!op.hasCalls()) {
            return "badge-empty";
        }
        final double pct = op.coveragePercent();
        if (pct >= 99.9) {
            return "badge-full";
        }
        if (pct > 0) {
            return "badge-partial";
        }
        return "badge-empty";
    }

    private static String coverageLabel(
            final CoverageComparator.OperationDetail op) {
        if (!op.hasCalls()) {
            return "No call";
        }
        final double pct = op.coveragePercent();
        if (pct >= 99.9) {
            return "Full";
        }
        if (pct > 0) {
            return "Partial";
        }
        return "Empty";
    }

    private static String coverageSlug(final String label) {
        switch (label) {
            case "No call": return "no-call";
            case "Full": return "full";
            case "Partial": return "partial";
            case "Empty": return "empty";
            default: return label.toLowerCase().replace(" ", "-");
        }
    }

    private static String pctColorClass(final double pct) {
        if (pct >= 90.0) {
            return "green";
        }
        if (pct >= 50.0) {
            return "orange";
        }
        return "red";
    }

    private static String barColorClass(final double pct) {
        if (pct >= 90.0) {
            return "progress-green";
        }
        if (pct >= 50.0) {
            return "progress-orange";
        }
        return "progress-red";
    }

    private static String esc(final String text) {
        if (text == null) {
            return "";
        }
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private static String fmt1(final double d) {
        return String.format("%.1f", d);
    }

    private static String joinInts(final List<Integer> values) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    // @formatter:off
    private static final String TEMPLATE = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>API Coverage Report</title>
<style>
:root {
  --bg: #f5f5f5; --fg: #333; --fg-strong: #222; --card-bg: #fff;
  --muted: #666; --muted2: #888; --border: #e0e0e0; --border-soft: #eee;
  --th-bg: #f0f0f0; --th-hover: #e4e4e4; --row-hover: #f8f9ff;
  --row-selected: #e3f2fd; --details-bg: #fafafa; --cond-th-bg: #e8e8e8;
  --code-fg: #222; --shadow: rgba(0,0,0,0.08); --shadow-hover: rgba(0,0,0,0.16);
  --accent: #1976d2; --track: #e0e0e0;
  --input-bg: #fff; --input-border: #ccc;
  --green: #2e7d32; --orange: #e65100; --red: #c62828;
  --green-bg: #e8f5e9; --orange-bg: #fff3e0; --red-bg: #ffebee;
  --green-bd: #a5d6a7; --red-bd: #ef9a9a;
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #16181d; --fg: #c9d1d9; --fg-strong: #f0f3f6; --card-bg: #21262d;
    --muted: #8b949e; --muted2: #6e7681; --border: #30363d;
    --border-soft: #262b31; --th-bg: #1c2128; --th-hover: #2d333b;
    --row-hover: #1f2730; --row-selected: #1c2f44; --details-bg: #1b1f24;
    --cond-th-bg: #2d333b; --code-fg: #e6edf3; --shadow: rgba(0,0,0,0.4);
    --shadow-hover: rgba(0,0,0,0.6); --accent: #58a6ff; --track: #30363d;
    --input-bg: #0d1117; --input-border: #30363d;
    --green: #56d364; --orange: #e3a008; --red: #f85149;
    --green-bg: #12261a; --orange-bg: #2b2410; --red-bg: #2d1416;
    --green-bd: #2ea043; --red-bd: #b62324;
  }
}
/* Manual theme override (beats the media query via higher specificity). */
:root[data-theme="light"] {
  --bg: #f5f5f5; --fg: #333; --fg-strong: #222; --card-bg: #fff;
  --muted: #666; --muted2: #888; --border: #e0e0e0; --border-soft: #eee;
  --th-bg: #f0f0f0; --th-hover: #e4e4e4; --row-hover: #f8f9ff;
  --row-selected: #e3f2fd; --details-bg: #fafafa; --cond-th-bg: #e8e8e8;
  --code-fg: #222; --shadow: rgba(0,0,0,0.08); --shadow-hover: rgba(0,0,0,0.16);
  --accent: #1976d2; --track: #e0e0e0; --input-bg: #fff; --input-border: #ccc;
  --green: #2e7d32; --orange: #e65100; --red: #c62828;
  --green-bg: #e8f5e9; --orange-bg: #fff3e0; --red-bg: #ffebee;
  --green-bd: #a5d6a7; --red-bd: #ef9a9a;
}
:root[data-theme="dark"] {
  --bg: #16181d; --fg: #c9d1d9; --fg-strong: #f0f3f6; --card-bg: #21262d;
  --muted: #8b949e; --muted2: #6e7681; --border: #30363d;
  --border-soft: #262b31; --th-bg: #1c2128; --th-hover: #2d333b;
  --row-hover: #1f2730; --row-selected: #1c2f44; --details-bg: #1b1f24;
  --cond-th-bg: #2d333b; --code-fg: #e6edf3; --shadow: rgba(0,0,0,0.4);
  --shadow-hover: rgba(0,0,0,0.6); --accent: #58a6ff; --track: #30363d;
  --input-bg: #0d1117; --input-border: #30363d;
  --green: #56d364; --orange: #e3a008; --red: #f85149;
  --green-bg: #12261a; --orange-bg: #2b2410; --red-bg: #2d1416;
  --green-bd: #2ea043; --red-bd: #b62324;
}
* { box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto,
    sans-serif; margin: 0; padding: 0; background: var(--bg);
    color: var(--fg); transition: background 0.2s, color 0.2s; }
.container { max-width: 1200px; margin: 0 auto; padding: 20px; }
h1 { color: var(--fg-strong); border-bottom: 2px solid var(--border);
    padding-bottom: 10px; }
h2 { color: var(--fg); margin: 24px 0 12px;
    border-bottom: 1px solid var(--border); padding-bottom: 6px; }
h3 { color: var(--fg); margin: 16px 0 8px; }
h4 { color: var(--muted); margin: 10px 0 6px; font-size: 0.9em; }
p.meta { color: var(--muted); font-size: 0.9em; margin: 0 0 20px; }
code { color: var(--code-fg); }
.green { color: var(--green); } .orange { color: var(--orange); }
.red { color: var(--red); } .muted { color: var(--muted2); }
.section-header { display: flex; justify-content: space-between;
    align-items: center; margin: 20px 0 10px; flex-wrap: wrap; gap: 8px; }
.section-header h2 { margin: 0; border: none; }
.ops-stats { font-size: 0.85em; color: var(--muted); }
.page-head { display: flex; justify-content: space-between;
    align-items: center; gap: 12px; }
.page-head h1 { flex: 1; }
.theme-btn { white-space: nowrap; }
.empty-banner { background: var(--orange-bg); color: var(--orange);
    border: 1px dashed var(--orange); border-radius: 8px; padding: 12px 16px;
    margin-bottom: 16px; font-size: 0.9em; }
.empty-banner code { color: var(--orange); }
.badge-deprecated { background: var(--th-bg); color: var(--muted);
    border: 1px solid var(--border); text-transform: uppercase;
    font-size: 0.66em; letter-spacing: 0.04em; }
.cond-type-card.clickable { cursor: pointer;
    transition: box-shadow 0.15s, transform 0.1s; }
.cond-type-card.clickable:hover { box-shadow: 0 3px 9px var(--shadow-hover);
    transform: translateY(-1px); }
.cond-type-card.filtered { box-shadow: 0 0 0 2px var(--accent); }
.gen-info { display: grid;
    grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
    gap: 4px 18px; background: var(--card-bg); border-radius: 8px;
    padding: 14px 18px; box-shadow: 0 1px 3px var(--shadow);
    font-size: 0.85em; margin-bottom: 16px; }
.gen-info > div { display: flex; justify-content: space-between; gap: 10px;
    padding: 3px 0; border-bottom: 1px solid var(--border-soft); }
.gi-k { color: var(--muted); }
.gi-v { color: var(--fg-strong); font-weight: 600; text-align: right;
    word-break: break-all; }

/* --- Overall banner --- */
.overall { display: flex; align-items: center; gap: 18px;
    background: var(--card-bg); border-radius: 10px; padding: 18px 22px;
    box-shadow: 0 1px 3px var(--shadow); margin-bottom: 20px; }
.overall-pct { font-size: 2.6em; font-weight: 700; line-height: 1; }
.overall-body { flex: 1; }
.overall-label { font-size: 0.95em; color: var(--muted); margin-bottom: 8px; }
.overall .progress-bar { height: 10px; max-width: none; border-radius: 5px; }
.overall .progress-fill { height: 10px; border-radius: 5px; }

/* --- Summary grid & clickable cards --- */
.summary-grid { display: grid;
    grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
    gap: 12px; margin-bottom: 24px; }
.summary-card { background: var(--card-bg); border-radius: 8px; padding: 14px;
    box-shadow: 0 1px 3px var(--shadow); text-align: center;
    cursor: pointer; transition: box-shadow 0.15s, transform 0.1s; }
.summary-card:hover { box-shadow: 0 3px 9px var(--shadow-hover);
    transform: translateY(-2px); }
.summary-card.filtered { box-shadow: 0 0 0 2px var(--accent); }
.summary-card .num { font-size: 1.6em; font-weight: bold; }
.summary-card .lbl { font-size: 0.8em; color: var(--muted); margin-top: 3px; }

/* --- Pie chart --- */
.chart-row { display: flex; gap: 24px; align-items: center;
    margin-bottom: 24px; flex-wrap: wrap; position: relative; }
.pie-svg { width: 160px; height: 160px; flex-shrink: 0; }
.pie-svg path { transition: opacity 0.15s, transform 0.15s;
    transform-origin: 100px 100px; cursor: pointer;
    stroke: var(--card-bg); stroke-width: 1.5; }
.pie-svg path.s-full { fill: var(--green); }
.pie-svg path.s-partial { fill: var(--orange); }
.pie-svg path.s-empty { fill: var(--red); }
.pie-legend-dot.s-full { background: var(--green); }
.pie-legend-dot.s-partial { background: var(--orange); }
.pie-legend-dot.s-empty { background: var(--red); }
.pie-svg path:hover { opacity: 0.85; transform: scale(1.05); }
.pie-center-text { font-size: 16px; font-weight: 600; fill: var(--fg-strong);
    text-anchor: middle; dominant-baseline: middle; }
.pie-center-sub { font-size: 11px; fill: var(--muted); text-anchor: middle;
    dominant-baseline: middle; }
.pie-legend { display: flex; flex-direction: column; gap: 6px; }
.pie-legend-item { display: flex; align-items: center; gap: 8px;
    font-size: 0.85em; cursor: pointer; padding: 4px 8px; border-radius: 6px;
    transition: background 0.15s; }
.pie-legend-item:hover { background: var(--th-bg); }
.pie-legend-dot { width: 12px; height: 12px; border-radius: 3px;
    flex-shrink: 0; }
.pie-legend-pct { font-weight: 600; min-width: 44px; }
.pie-tooltip { position: absolute; pointer-events: none;
    background: rgba(0,0,0,0.82); color: #fff; border-radius: 6px;
    padding: 6px 10px; font-size: 0.82em; white-space: nowrap;
    opacity: 0; transition: opacity 0.12s; z-index: 10; }
.pie-tooltip.show { opacity: 1; }

/* --- Tag cards --- */
.tag-grid { display: grid;
    grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
    gap: 12px; margin-bottom: 8px; }
.tag-card { background: var(--card-bg); border-radius: 8px; padding: 12px 14px;
    box-shadow: 0 1px 3px var(--shadow); cursor: pointer;
    transition: box-shadow 0.15s, transform 0.1s; }
.tag-card:hover { box-shadow: 0 3px 9px var(--shadow-hover);
    transform: translateY(-2px); }
.tag-card.filtered { box-shadow: 0 0 0 2px var(--accent); }
.tag-card-head { display: flex; justify-content: space-between;
    align-items: baseline; margin-bottom: 6px; }
.tag-card-name { font-weight: 600; font-size: 0.95em; }
.tag-card-head .num { font-weight: 700; }
.tag-card-stats { display: flex; flex-wrap: wrap; gap: 8px;
    font-size: 0.74em; margin-top: 8px; }

/* --- Controls --- */
.controls { display: flex; gap: 10px; margin: 0 0 12px; flex-wrap: wrap;
    align-items: center; }
.search-box { flex: 1; min-width: 200px; padding: 7px 12px;
    border: 1px solid var(--input-border); border-radius: 18px;
    background: var(--input-bg); color: var(--fg); font-size: 0.88em; }
.search-box:focus { outline: none; border-color: var(--accent); }
.btn { border: 1px solid var(--input-border); background: var(--card-bg);
    color: var(--fg); border-radius: 16px; padding: 5px 12px;
    font-size: 0.82em; cursor: pointer; transition: all 0.15s; }
.btn:hover { border-color: var(--accent); color: var(--accent); }
.filter-bar { display: flex; gap: 6px; margin: 0 0 12px; flex-wrap: wrap; }
.filter-btn { border: 1px solid var(--input-border); background: var(--card-bg);
    color: var(--fg); border-radius: 16px; padding: 4px 12px; font-size: 0.82em;
    cursor: pointer; transition: all 0.15s; }
.filter-btn:hover { border-color: var(--accent); color: var(--accent); }
.filter-btn.active { background: var(--accent); color: #fff;
    border-color: var(--accent); }
.filter-btn .count { font-weight: 600; }
.active-tag-chip { display: none; align-items: center; gap: 6px;
    background: var(--accent); color: #fff; border-radius: 14px;
    padding: 3px 10px; font-size: 0.8em; }
.active-tag-chip.show { display: inline-flex; }
.active-tag-chip button { background: none; border: none; color: #fff;
    cursor: pointer; font-size: 1em; line-height: 1; padding: 0; }

/* --- Table --- */
table { width: 100%; border-collapse: collapse; margin: 10px 0 20px;
    background: var(--card-bg); border-radius: 8px; overflow: hidden;
    box-shadow: 0 1px 3px var(--shadow); }
th { background: var(--th-bg); color: var(--fg); padding: 8px 12px;
    text-align: left; font-weight: 600; font-size: 0.85em;
    text-transform: uppercase; cursor: pointer; user-select: none;
    white-space: nowrap; }
.toolbar { position: sticky; top: 0; z-index: 5; background: var(--bg);
    padding: 10px 0 4px; margin-bottom: 4px;
    border-bottom: 1px solid var(--border); }
.toolbar .controls, .toolbar .filter-bar { margin-bottom: 8px; }
th:hover { background: var(--th-hover); }
th .sort-icon { margin-left: 4px; font-size: 0.7em; opacity: 0.4; }
th.sorted .sort-icon { opacity: 1; }
td { padding: 8px 12px; border-bottom: 1px solid var(--border-soft);
    font-size: 0.9em; vertical-align: middle; }
tr:last-child td { border-bottom: none; }
tr.op-row:hover { background: var(--row-hover); }
tr.op-row.selected { background: var(--row-selected); }
th:nth-child(1), td:nth-child(1) { width: 60px; }
th:nth-child(5), td:nth-child(5) { width: 50px; text-align: center; }
th:nth-child(6), td:nth-child(6) { width: 70px; text-align: center; }
.op-summary { color: var(--muted); font-size: 0.82em; margin-top: 2px; }
.tag-pill { display: inline-block; padding: 2px 8px; border-radius: 10px;
    font-size: 0.76em; background: var(--th-bg); color: var(--muted);
    white-space: nowrap; }
.no-results { text-align: center; color: var(--muted); padding: 24px;
    display: none; }

.method-tag { display: inline-block; padding: 2px 6px; border-radius: 3px;
    font-size: 0.8em; font-weight: bold; color: #fff; min-width: 44px;
    text-align: center; }
.GET { background: #1976d2; } .POST { background: #388e3c; }
.PUT { background: #e65100; } .PATCH { background: #6a1b9a; }
.DELETE { background: #c62828; } .HEAD { background: #555; }
.badge { display: inline-block; padding: 2px 8px; border-radius: 10px;
    font-size: 0.78em; font-weight: 600; }
.badge-full { background: var(--green-bg); color: var(--green); }
.badge-partial { background: var(--orange-bg); color: var(--orange); }
.badge-empty { background: var(--red-bg); color: var(--red); }
.badge-undeclared { background: var(--orange-bg); color: var(--orange);
    border: 1px dashed var(--orange); margin-left: 6px; }
.status-undeclared { background: var(--orange-bg); color: var(--orange);
    border: 1px dashed var(--orange); }
.undeclared-h { color: var(--orange); }
.op-row { cursor: pointer; }
.op-row.hidden { display: none; }
.op-details { display: none; background: var(--details-bg); }
.op-details.open { display: table-row; }
.op-details td { padding: 0; }
.op-details-inner { padding: 12px 16px 16px 36px;
    border-bottom: 1px solid var(--border); }
.cond-head { display: flex; align-items: center; justify-content: space-between;
    gap: 10px; margin-top: 10px; }
.cond-head h4 { margin: 0; }
.cond-cov-label { font-size: 0.85em; font-weight: 600; white-space: nowrap; }
.cond-cov-bar { max-width: none; height: 6px; margin: 4px 0 8px; }
.cond-cov-bar .progress-fill { height: 6px; }
.cond-table { width: 100%; margin: 4px 0 8px; box-shadow: none;
    border-radius: 6px; overflow: hidden; }
.cond-table th { background: var(--cond-th-bg); font-size: 0.78em;
    padding: 5px 10px; cursor: default; position: static;
    text-transform: uppercase; }
.cond-table td { font-size: 0.85em; padding: 5px 10px;
    border-bottom: 1px solid var(--border-soft); }
.cond-row-covered { background: var(--green-bg); }
.cond-row-covered td:first-child { border-left: 3px solid var(--green); }
.cond-row-covered .cond-icon { color: var(--green); font-weight: 700; }
.cond-row-uncovered { background: var(--red-bg); }
.cond-row-uncovered td:first-child { border-left: 3px solid var(--red); }
.cond-row-uncovered .cond-icon { color: var(--red); font-weight: 700; }
.cond-det { color: var(--muted); font-size: 0.82em; }
.status-codes { display: flex; gap: 4px; flex-wrap: wrap;
    margin-bottom: 8px; }
.status-code { display: inline-block; padding: 1px 6px; border-radius: 3px;
    font-size: 0.8em; }
.status-seen { background: var(--green-bg); color: var(--green);
    border: 1px solid var(--green-bd); }
.status-missed { background: var(--red-bg); color: var(--red);
    border: 1px solid var(--red-bd); }
.cond-type-card { background: var(--card-bg); border-radius: 6px;
    padding: 10px 14px; margin: 6px 0;
    box-shadow: 0 1px 2px var(--shadow); }
.cond-type-header { display: flex; justify-content: space-between;
    align-items: center; }
.cond-type-name { font-weight: 600; font-size: 0.9em; }
.cond-type-desc { font-size: 0.78em; color: var(--muted2); margin-top: 2px; }
.cond-type-stat { font-size: 0.9em; white-space: nowrap; }
.cond-type-stat .num { font-weight: 600; }

.progress-bar { height: 4px; border-radius: 2px; background: var(--track);
    margin: 4px 0 2px; max-width: 140px; }
.progress-fill { height: 4px; border-radius: 2px; }
.progress-green .progress-fill { background: var(--green); }
.progress-orange .progress-fill { background: var(--orange); }
.progress-red .progress-fill { background: var(--red); }
</style>
</head>
<body>
<div class="container">
<div class="page-head">
  <h1>API Coverage Report</h1>
  <button class="btn theme-btn" id="themeToggle"
    title="Toggle light / dark / auto theme">🌓 Auto</button>
</div>
<p class="meta">Spec: {{SPEC_SOURCE}} ({{SPEC_VERSION}})</p>

{{EMPTY_BANNER}}

<div class="overall">
  <div class="overall-pct {{COND_PCT_CSS}}">{{COND_PCT}}%</div>
  <div class="overall-body">
    <div class="overall-label">Overall conditions coverage &mdash;
      {{COND_COVERED}}/{{COND_TOTAL}} conditions covered across
      {{ALL_OPS}} operations</div>
    <div class="progress-bar {{OVERALL_BAR_CSS}}">
      <div class="progress-fill" style="width:{{COND_PCT}}%"></div>
    </div>
  </div>
</div>

<div class="section-header">
  <h2>Summary</h2>
  <span class="ops-stats">All operations: {{ALL_OPS}}</span>
</div>

<div class="chart-row" id="chartRow">
  <svg viewBox="0 0 200 200" class="pie-svg" id="pieChart"></svg>
  <div class="pie-tooltip" id="pieTooltip"></div>
  <div class="pie-legend" id="pieLegend"></div>
</div>

<div class="summary-grid" id="summaryGrid">
  <div class="summary-card" data-filter="full" title="Click to show Full coverage operations">
    <div class="num {{FULL_PCT_CSS}}">{{FULL_PCT}}%</div>
    <div class="lbl">Full coverage</div>
  </div>
  <div class="summary-card" data-filter="partial" title="Click to show Partial coverage operations">
    <div class="num {{PARTIAL_PCT_CSS}}">{{PARTIAL_PCT}}%</div>
    <div class="lbl">Partial coverage</div>
  </div>
  <div class="summary-card" data-filter="empty" title="Click to show Empty coverage operations">
    <div class="num {{EMPTY_PCT_CSS}}">{{EMPTY_PCT}}%</div>
    <div class="lbl">Empty coverage</div>
  </div>
  <div class="summary-card" data-filter="all" title="Click to show all operations">
    <div class="num">{{ALL_OPS}}</div>
    <div class="lbl">All operations</div>
  </div>
  <div class="summary-card" data-filter="no-call" title="Click to show No-call operations">
    <div class="num red">{{NO_CALL_OPS}}</div>
    <div class="lbl">Operations without calls</div>
  </div>
  <div class="summary-card" data-filter="missed" title="Click for details">
    <div class="num red">{{MISSED_REQ}}</div>
    <div class="lbl">Missed conditions</div>
  </div>
  <div class="summary-card" data-filter="undeclared"
    title="Returned status codes not in the spec (not counted in coverage)">
    <div class="num orange">{{UNDECLARED_OPS}}</div>
    <div class="lbl">Undeclared statuses</div>
  </div>
  <div class="summary-card" title="Conditions coverage">
    <div class="num {{COND_PCT_CSS}}">{{COND_PCT}}%
      ({{COND_COVERED}}/{{COND_TOTAL}})</div>
    <div class="lbl">Conditions covered</div>
  </div>
  <div class="summary-card" data-filter="missed" title="Show operations with uncovered conditions">
    <div class="num red">{{COND_UNCOVERED}}</div>
    <div class="lbl">Conditions uncovered</div>
  </div>
</div>

<h2>Conditions details</h2>
{{CONDITION_TYPES}}

<div class="section-header">
  <h2>Coverage by tag</h2>
  <span class="ops-stats">{{TAG_COUNT}} tags</span>
</div>
<div class="tag-grid" id="tagGrid">
{{TAG_CARDS}}
</div>

<div class="section-header">
  <h2>Operations details</h2>
  <span class="ops-stats" id="opsStats">All: {{OPS_TOTAL}} | Full: {{FULL_COUNT}}
    | Partial: {{PARTIAL_COUNT}} | Empty: {{EMPTY_COUNT}}
    | No call: {{OPS_WITHOUT_CALLS}} | Missed: {{MISSED_REQUESTS}}
    | Deprecated: {{DEPRECATED_COUNT}}</span>
</div>

<div class="toolbar">
<div class="controls">
  <input type="text" id="searchBox" class="search-box"
    placeholder="Search by method, path, tag or summary...">
  <button class="btn" id="expandAll">Expand all</button>
  <button class="btn" id="collapseAll">Collapse all</button>
  <span class="active-tag-chip" id="activeTagChip">
    <span id="activeTagLabel"></span>
    <button type="button" id="clearTag" title="Clear tag filter">&times;</button>
  </span>
  <span class="active-tag-chip" id="activeTypeChip">
    <span id="activeTypeLabel"></span>
    <button type="button" id="clearType" title="Clear condition filter">&times;</button>
  </span>
</div>

<div class="filter-bar" id="filterBar">
  <button class="filter-btn active" data-filter="all">All
    <span class="count">{{OPS_TOTAL}}</span></button>
  <button class="filter-btn" data-filter="full">Full
    <span class="count">{{FULL_COUNT}}</span></button>
  <button class="filter-btn" data-filter="partial">Partial
    <span class="count">{{PARTIAL_COUNT}}</span></button>
  <button class="filter-btn" data-filter="no-call">No call
    <span class="count">{{OPS_WITHOUT_CALLS}}</span></button>
  <button class="filter-btn" data-filter="empty">Empty
    <span class="count">{{EMPTY_COUNT}}</span></button>
  <button class="filter-btn" data-filter="undeclared"
    title="Operations returning status codes not in the spec">Undeclared
    <span class="count">{{UNDECLARED_OPS}}</span></button>
</div>
</div>

<table id="opsTable">
  <thead>
    <tr>
      <th onclick="sortTable(0)">Method <span class="sort-icon">&#x25B4;&#x25BE;</span></th>
      <th onclick="sortTable(1)">Path <span class="sort-icon">&#x25B4;&#x25BE;</span></th>
      <th onclick="sortTable(2)">Tag <span class="sort-icon">&#x25B4;&#x25BE;</span></th>
      <th onclick="sortTable(3)">Coverage <span class="sort-icon">&#x25B4;&#x25BE;</span></th>
      <th onclick="sortTable(4)">Calls <span class="sort-icon">&#x25B4;&#x25BE;</span></th>
      <th onclick="sortTable(5)">Conditions <span class="sort-icon">&#x25B4;&#x25BE;</span></th>
    </tr>
  </thead>
  <tbody id="opsBody">
    {{OPS_ROWS}}
  </tbody>
</table>
<div class="no-results" id="noResults">No operations match the current filters.</div>

<h2>Generation info</h2>
{{GEN_INFO}}

<script>
/* ==== Theme toggle: auto -> light -> dark (persisted) ==== */
(function() {
  var order = ['auto', 'light', 'dark'];
  var labels = { auto: '🌓 Auto', light: '☀ Light', dark: '🌙 Dark' };
  var btn = document.getElementById('themeToggle');
  function apply(mode) {
    if (mode === 'auto') {
      document.documentElement.removeAttribute('data-theme');
    } else {
      document.documentElement.setAttribute('data-theme', mode);
    }
    btn.textContent = labels[mode];
  }
  var saved = null;
  try { saved = localStorage.getItem('coverageTheme'); } catch (e) { saved = null; }
  apply(order.indexOf(saved) >= 0 ? saved : 'auto');
  btn.addEventListener('click', function() {
    var cur = document.documentElement.getAttribute('data-theme') || 'auto';
    var next = order[(order.indexOf(cur) + 1) % order.length];
    apply(next);
    try { localStorage.setItem('coverageTheme', next); } catch (e) { /* ignore */ }
  });
})();

/* ==== Filter state ==== */
var state = { cov: 'all', tag: null, search: '', missedType: null };

/* ==== SVG Pie Chart ==== */
(function() {
  var pieces = [
    { label: 'Full', pct: {{FULL_PCT}}, cls: 's-full' },
    { label: 'Partial', pct: {{PARTIAL_PCT}}, cls: 's-partial' },
    { label: 'Empty', pct: {{EMPTY_PCT}}, cls: 's-empty' }
  ];

  var svg = document.getElementById('pieChart');
  var tooltip = document.getElementById('pieTooltip');
  var chartRow = document.getElementById('chartRow');
  var cx = 100, cy = 100, r = 75;

  var centerText = document.createElementNS('http://www.w3.org/2000/svg', 'text');
  centerText.setAttribute('x', cx);
  centerText.setAttribute('y', cy - 6);
  centerText.setAttribute('class', 'pie-center-text');
  centerText.textContent = {{COND_PCT}} + '%';

  var centerSub = document.createElementNS('http://www.w3.org/2000/svg', 'text');
  centerSub.setAttribute('x', cx);
  centerSub.setAttribute('y', cy + 14);
  centerSub.setAttribute('class', 'pie-center-sub');
  centerSub.textContent = 'conditions';

  svg.appendChild(centerText);
  svg.appendChild(centerSub);

  function polar(cx, cy, r, deg) {
    var rad = (deg - 90) * Math.PI / 180;
    return { x: cx + r * Math.cos(rad), y: cy + r * Math.sin(rad) };
  }

  function arcPath(startDeg, endDeg) {
    var s = polar(cx, cy, r, endDeg);
    var e = polar(cx, cy, r, startDeg);
    var large = endDeg - startDeg > 180 ? 1 : 0;
    return 'M ' + cx + ' ' + cy
      + ' L ' + s.x + ' ' + s.y
      + ' A ' + r + ' ' + r + ' 0 ' + large + ' 0 ' + e.x + ' ' + e.y
      + ' Z';
  }

  var startAngle = 0;
  for (var i = 0; i < pieces.length; i++) {
    var p = pieces[i];
    if (p.pct === 0) continue;
    var sweep = p.pct / 100 * 360;
    var endAngle = startAngle + sweep;

    var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', arcPath(startAngle, endAngle));
    path.setAttribute('class', 'pie-slice ' + p.cls);

    (function(piece) {
      path.addEventListener('mouseenter', function(e) {
        this.style.opacity = '0.88';
        tooltip.textContent = piece.label + ': ' + piece.pct.toFixed(1) + '%';
        tooltip.classList.add('show');
        var rect = chartRow.getBoundingClientRect();
        tooltip.style.left = (e.clientX - rect.left + 12) + 'px';
        tooltip.style.top = (e.clientY - rect.top - 28) + 'px';
      });
      path.addEventListener('mousemove', function(e) {
        var rect = chartRow.getBoundingClientRect();
        tooltip.style.left = (e.clientX - rect.left + 12) + 'px';
        tooltip.style.top = (e.clientY - rect.top - 28) + 'px';
      });
      path.addEventListener('mouseleave', function() {
        this.style.opacity = '1';
        tooltip.classList.remove('show');
      });
      path.addEventListener('click', function() {
        var filterVal = piece.label.toLowerCase() === 'no call'
          ? 'no-call' : piece.label.toLowerCase();
        setCovFilter(filterVal);
      });
    })(p);

    svg.appendChild(path);
    startAngle = endAngle;
  }

  var legend = document.getElementById('pieLegend');
  for (var i = 0; i < pieces.length; i++) {
    var p = pieces[i];
    if (p.pct === 0) continue;
    var item = document.createElement('div');
    item.className = 'pie-legend-item';
    item.innerHTML = '<span class="pie-legend-dot ' + p.cls + '"></span>'
      + '<span class="pie-legend-pct">' + p.pct.toFixed(1) + '%</span>'
      + '<span>' + p.label + '</span>';
    (function(piece) {
      item.addEventListener('click', function() {
        var filterVal = piece.label.toLowerCase() === 'no call'
          ? 'no-call' : piece.label.toLowerCase();
        setCovFilter(filterVal);
      });
    })(p);
    legend.appendChild(item);
  }
})();

/* ==== Summary card filter ==== */
(function() {
  var cards = document.querySelectorAll('#summaryGrid .summary-card');
  cards.forEach(function(card) {
    card.addEventListener('click', function() {
      var f = this.dataset.filter;
      if (f) { setCovFilter(f); }
    });
  });
})();

/* ==== Tag cards filter ==== */
(function() {
  var cards = document.querySelectorAll('#tagGrid .tag-card');
  cards.forEach(function(card) {
    card.addEventListener('click', function() {
      var tag = this.dataset.tag;
      state.tag = (state.tag === tag) ? null : tag;
      applyFilters();
      document.getElementById('opsTable').scrollIntoView({ behavior: 'smooth' });
    });
  });
})();

/* ==== Condition-type cards filter (operations missing this type) ==== */
(function() {
  document.querySelectorAll('.cond-type-card.clickable').forEach(function(card) {
    card.addEventListener('click', function() {
      var type = this.dataset.type;
      state.missedType = (state.missedType === type) ? null : type;
      applyFilters();
      document.getElementById('opsTable').scrollIntoView({ behavior: 'smooth' });
    });
  });
})();

/* ==== Cov filter setter ==== */
function setCovFilter(filterVal) {
  state.cov = filterVal;
  applyFilters();
  document.getElementById('opsTable').scrollIntoView({ behavior: 'smooth' });
}

/* ==== Unified filter engine ==== */
function applyFilters() {
  var rows = document.querySelectorAll('#opsBody .op-row');
  var buttons = document.querySelectorAll('#filterBar .filter-btn');
  var cards = document.querySelectorAll('#summaryGrid .summary-card');
  var tagCards = document.querySelectorAll('#tagGrid .tag-card');

  buttons.forEach(function(b) {
    b.classList.toggle('active', b.dataset.filter === state.cov);
  });
  cards.forEach(function(c) {
    c.classList.toggle('filtered', c.dataset.filter === state.cov);
  });
  tagCards.forEach(function(c) {
    c.classList.toggle('filtered', c.dataset.tag === state.tag);
  });
  document.querySelectorAll('.cond-type-card.clickable').forEach(function(c) {
    c.classList.toggle('filtered', c.dataset.type === state.missedType);
  });

  var chip = document.getElementById('activeTagChip');
  if (state.tag) {
    chip.classList.add('show');
    document.getElementById('activeTagLabel').textContent = 'Tag: ' + state.tag;
  } else {
    chip.classList.remove('show');
  }
  var typeChip = document.getElementById('activeTypeChip');
  if (state.missedType) {
    typeChip.classList.add('show');
    document.getElementById('activeTypeLabel').textContent =
      'Missing: ' + state.missedType;
  } else {
    typeChip.classList.remove('show');
  }

  var visibleCount = 0;
  rows.forEach(function(row) {
    var match = true;

    // Coverage dimension
    if (state.cov !== 'all') {
      var cov = row.dataset.cov;
      var covMatch = cov === state.cov;
      if (!covMatch && state.cov === 'empty' && cov === 'no-call') {
        covMatch = true;
      }
      if (state.cov === 'missed') {
        var condCell = row.cells[5];
        covMatch = false;
        if (condCell) {
          var parts = condCell.textContent.trim().split('/');
          covMatch = parts.length === 2
            && parseInt(parts[0]) < parseInt(parts[1]);
        }
      }
      if (state.cov === 'undeclared') {
        covMatch = (row.dataset.undeclared || '') !== '';
      }
      match = match && covMatch;
    }

    // Tag dimension
    if (state.tag) {
      match = match && row.dataset.tag === state.tag;
    }

    // Missing-condition-type dimension
    if (state.missedType) {
      match = match && (row.dataset.missed || '')
        .indexOf('||' + state.missedType + '||') !== -1;
    }

    // Search dimension
    if (state.search) {
      match = match && row.dataset.search.indexOf(state.search) !== -1;
    }

    row.classList.toggle('hidden', !match);
    // Keep any open detail row in sync with its parent
    var detail = row.nextElementSibling;
    if (detail && detail.classList.contains('op-details')) {
      detail.style.display = match && detail.classList.contains('open')
        ? 'table-row' : '';
    }
    if (match) visibleCount++;
  });

  document.getElementById('noResults').style.display =
    visibleCount === 0 ? 'block' : 'none';

  var stats = document.getElementById('opsStats');
  if (stats) {
    var total = rows.length;
    var label = state.cov.charAt(0).toUpperCase()
      + state.cov.slice(1).replace('-', ' ');
    var txt = 'Showing: ' + visibleCount + '/' + total + ' | Filter: ' + label;
    if (state.tag) txt += ' | Tag: ' + state.tag;
    if (state.missedType) txt += ' | Missing: ' + state.missedType;
    if (state.search) txt += ' | Search: "' + state.search + '"';
    stats.textContent = txt;
  }
  syncHash();
}

/* ==== Deep-link: reflect state + open rows into location.hash ==== */
var suppressHash = false;
function openOpIds() {
  var ids = [];
  document.querySelectorAll('.op-details.open').forEach(function(el) {
    ids.push(el.id.replace('op-detail-', ''));
  });
  return ids;
}
function syncHash() {
  if (suppressHash) return;
  var parts = [];
  if (state.cov && state.cov !== 'all') parts.push('cov=' + state.cov);
  if (state.tag) parts.push('tag=' + encodeURIComponent(state.tag));
  if (state.missedType) parts.push('type=' + encodeURIComponent(state.missedType));
  if (state.search) parts.push('q=' + encodeURIComponent(state.search));
  var open = openOpIds();
  if (open.length) parts.push('open=' + open.join(','));
  var hash = parts.join('&');
  history.replaceState(null, '', hash ? '#' + hash : location.pathname);
}
function restoreFromHash() {
  var raw = location.hash.replace(/^#/, '');
  if (!raw) return;
  suppressHash = true;
  var params = {};
  raw.split('&').forEach(function(kv) {
    var i = kv.indexOf('=');
    if (i > 0) params[kv.slice(0, i)] = decodeURIComponent(kv.slice(i + 1));
  });
  state.cov = params.cov || 'all';
  state.tag = params.tag || null;
  state.missedType = params.type || null;
  state.search = params.q || '';
  if (state.search) document.getElementById('searchBox').value = state.search;
  applyFilters();
  if (params.open) {
    var opRows = document.querySelectorAll('.op-row');
    params.open.split(',').forEach(function(id) {
      var d = document.getElementById('op-detail-' + id);
      if (d) {
        d.classList.add('open');
        d.style.display = 'table-row';
        if (opRows[id]) opRows[id].classList.add('selected');
      }
    });
  }
  suppressHash = false;
  syncHash();
}

/* ==== Filter buttons ==== */
(function() {
  var buttons = document.querySelectorAll('#filterBar .filter-btn');
  buttons.forEach(function(btn) {
    btn.addEventListener('click', function() {
      state.cov = this.dataset.filter;
      applyFilters();
    });
  });
})();

/* ==== Search box ==== */
(function() {
  var box = document.getElementById('searchBox');
  box.addEventListener('input', function() {
    state.search = this.value.trim().toLowerCase();
    applyFilters();
  });
})();

/* ==== Clear tag / type chips ==== */
document.getElementById('clearTag').addEventListener('click', function() {
  state.tag = null;
  applyFilters();
});
document.getElementById('clearType').addEventListener('click', function() {
  state.missedType = null;
  applyFilters();
});

/* ==== Expand / collapse all ==== */
document.getElementById('expandAll').addEventListener('click', function() {
  document.querySelectorAll('#opsBody .op-row').forEach(function(row, i) {
    if (row.classList.contains('hidden')) return;
    var detail = row.nextElementSibling;
    if (detail && detail.classList.contains('op-details')) {
      detail.classList.add('open');
      detail.style.display = 'table-row';
      row.classList.add('selected');
    }
  });
  syncHash();
});
document.getElementById('collapseAll').addEventListener('click', function() {
  document.querySelectorAll('.op-details.open').forEach(function(el) {
    el.classList.remove('open');
    el.style.display = '';
  });
  document.querySelectorAll('.op-row.selected').forEach(function(el) {
    el.classList.remove('selected');
  });
  syncHash();
});

/* ==== Sort ==== */
var sortState = {};

function sortTable(colIdx) {
  var tbody = document.getElementById('opsBody');
  // Sort the (row, detail) pairs together so details stay attached
  var rows = Array.from(tbody.querySelectorAll('.op-row'));
  var key = 'col' + colIdx;
  var dir = sortState[key] === 'asc' ? 'desc' : 'asc';
  sortState = {};
  sortState[key] = dir;

  document.querySelectorAll('#opsTable th').forEach(function(th, i) {
    th.classList.toggle('sorted', i === colIdx);
  });

  rows.sort(function(a, b) {
    var ca = a.cells[colIdx];
    var cb = b.cells[colIdx];
    if (!ca || !cb) return 0;
    var va = ca.textContent.trim();
    var vb = cb.textContent.trim();

    if (colIdx === 4 || colIdx === 5) {
      va = parseFloat(va) || 0;
      vb = parseFloat(vb) || 0;
      return dir === 'asc' ? va - vb : vb - va;
    }
    if (colIdx === 0) {
      var ta = a.querySelector('.method-tag');
      var tb = b.querySelector('.method-tag');
      va = ta ? ta.textContent : va;
      vb = tb ? tb.textContent : vb;
    }
    va = va.toLowerCase();
    vb = vb.toLowerCase();
    if (va < vb) return dir === 'asc' ? -1 : 1;
    if (va > vb) return dir === 'asc' ? 1 : -1;
    return 0;
  });

  rows.forEach(function(r) {
    var detail = r.nextElementSibling;
    tbody.appendChild(r);
    if (detail && detail.classList.contains('op-details')) {
      tbody.appendChild(detail);
    }
  });
}

/* ==== Toggle details (multiple rows may stay open at once) ==== */
function toggleOp(idx) {
  var detailEl = document.getElementById('op-detail-' + idx);
  if (!detailEl) return;
  var opRows = document.querySelectorAll('.op-row');
  var isOpen = detailEl.classList.contains('open');
  if (isOpen) {
    detailEl.classList.remove('open');
    detailEl.style.display = '';
    if (opRows[idx]) opRows[idx].classList.remove('selected');
  } else {
    detailEl.classList.add('open');
    detailEl.style.display = 'table-row';
    if (opRows[idx]) opRows[idx].classList.add('selected');
  }
  syncHash();
}

/* ==== Restore filters + open rows from URL hash on load ==== */
restoreFromHash();
</script>

</div>
</body>
</html>""";
    // @formatter:on
}
