package net.talaatharb.harness.reply;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Explicit, deterministic checks, not a model judge or an inferred interpretation of a prompt. */
public final class ResponseContract {
    private static final Pattern ITEM = Pattern.compile("^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+).+");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+.+");

    private ResponseContract() {
    }

    public static List<String> errors(String text, ReplySettings settings) {
        List<String> errors = new ArrayList<>();
        String content = text == null ? "" : text.strip();
        List<String> lines = content.lines().filter(line -> !line.isBlank()).toList();
        if (settings.exactListItems > 0) {
            long count = lines.stream().filter(line -> ITEM.matcher(line).matches()).count();
            if (count != settings.exactListItems || count != lines.size()) {
                errors.add("Reply with only a list of exactly " + settings.exactListItems + " items.");
            }
        }
        if (settings.maxBulletPoints > 0) {
            long count = lines.stream().filter(line -> BULLET.matcher(line).matches()).count();
            if (count > settings.maxBulletPoints) {
                errors.add("Use at most " + settings.maxBulletPoints + " bullet points.");
            }
        }
        List<String> allowed = allowed(settings);
        if (!allowed.isEmpty() && !allowed.contains(content)) {
            errors.add("Reply with exactly one of: " + String.join(", ", allowed) + ".");
        }
        return errors;
    }

    public static String instructions(ReplySettings settings) {
        List<String> rules = new ArrayList<>();
        if (settings.exactListItems > 0) {
            rules.add("Reply only with a list of exactly " + settings.exactListItems + " items, one per line.");
        }
        if (settings.maxBulletPoints > 0) {
            rules.add("Use at most " + settings.maxBulletPoints + " bullet points.");
        }
        if (!allowed(settings).isEmpty()) {
            rules.add("Reply with exactly one of: " + String.join(", ", allowed(settings)) + ".");
        }
        return String.join("\n", rules);
    }

    private static List<String> allowed(ReplySettings settings) {
        return settings.allowedReplies == null ? List.of() : Arrays.stream(settings.allowedReplies.split("\\|"))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
