package net.talaatharb.harness.prompt;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal {@code {{variable}}} template renderer. Unknown variables are left untouched. */
public final class PromptTemplate {
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.-]+)\\s*}}");

    private PromptTemplate() {
    }

    public static String render(String template, Map<String, String> variables) {
        if (template == null) {
            return "";
        }
        Matcher m = VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = variables.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group(0)));
        }
        m.appendTail(out);
        return out.toString();
    }

    public static Map<String, String> standardVariables(String model) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, String> vars = new HashMap<>();
        vars.put("date", now.format(DateTimeFormatter.ISO_LOCAL_DATE));
        vars.put("time", now.format(DateTimeFormatter.ofPattern("HH:mm")));
        vars.put("model", model == null ? "unknown" : model);
        vars.put("os", System.getProperty("os.name", "unknown"));
        vars.put("user", System.getProperty("user.name", "user"));
        return vars;
    }
}
