package net.talaatharb.harness.web;

import java.util.List;
import java.util.Map;
import net.talaatharb.harness.tools.ToolArguments;

/** Argument parsing and formatting shared by the two web tools. */
final class WebToolArgs {
    private WebToolArgs() {
    }

    static String query(Map<String, Object> args) {
        Object q = args.get("query");
        if (q != null && !(q instanceof String)) {
            throw new IllegalArgumentException("'query' must be a string");
        }
        return q == null ? "" : ((String) q).strip();
    }

    static int maxPages(Map<String, Object> args, int configured) {
        return Math.min(configured, ToolArguments.integer(args, "maxPages", configured, Integer.MAX_VALUE));
    }

    static void appendProblems(StringBuilder sb, String heading, List<WebResearchService.Skipped> items) {
        if (items.isEmpty()) {
            return;
        }
        sb.append("\n\n").append(heading).append(":\n");
        for (WebResearchService.Skipped s : items) {
            sb.append("- ").append(s.url()).append(" — ").append(s.reason()).append('\n');
        }
    }
}
