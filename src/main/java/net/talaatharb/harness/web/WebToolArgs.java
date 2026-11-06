package net.talaatharb.harness.web;

import java.util.List;
import java.util.Map;

/** Argument parsing and formatting shared by the two web tools. */
final class WebToolArgs {
    private WebToolArgs() {
    }

    static String query(Map<String, Object> args) {
        Object q = args.get("query");
        return q == null ? "" : String.valueOf(q).strip();
    }

    static int maxPages(Map<String, Object> args, int configured) {
        Object v = args.get("maxPages");
        try {
            int n = v instanceof Number num ? num.intValue() : v == null ? configured : Integer.parseInt(String.valueOf(v).strip());
            return Math.max(1, Math.min(n, configured));
        } catch (NumberFormatException e) {
            return configured;
        }
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
