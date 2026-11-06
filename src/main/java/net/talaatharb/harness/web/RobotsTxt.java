package net.talaatharb.harness.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Minimal robots.txt parser (RFC 9309): longest matching rule wins, {@code Allow} wins ties, {@code *} and {@code $}. */
public final class RobotsTxt {
    private record Rule(boolean allow, Pattern pattern, int length) {
    }

    public static final RobotsTxt ALLOW_ALL = new RobotsTxt(List.of());
    public static final RobotsTxt DISALLOW_ALL = new RobotsTxt(List.of(new Rule(false, Pattern.compile(".*"), 1)));

    private final List<Rule> rules;

    private RobotsTxt(List<Rule> rules) {
        this.rules = rules;
    }

    /** @param agent product token of this crawler, e.g. {@code HarnessBot} */
    public static RobotsTxt parse(String body, String agent) {
        String token = agent.toLowerCase(Locale.ROOT);
        List<Rule> specific = new ArrayList<>();
        List<Rule> general = new ArrayList<>();
        boolean sawSpecific = false;
        List<String> groupAgents = new ArrayList<>();
        boolean groupHasRules = false;
        for (String raw : (body == null ? "" : body).split("\\R")) {
            String line = raw.contains("#") ? raw.substring(0, raw.indexOf('#')) : raw;
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            switch (key) {
            case "user-agent" -> {
                if (groupHasRules) {
                    groupAgents.clear();
                    groupHasRules = false;
                }
                groupAgents.add(value.toLowerCase(Locale.ROOT));
            }
            case "allow", "disallow" -> {
                groupHasRules = true;
                if (value.isEmpty()) {
                    continue; // an empty Disallow allows everything; an empty Allow adds nothing
                }
                Rule rule = new Rule(key.equals("allow"), toPattern(value), value.length());
                if (groupAgents.stream().anyMatch(a -> !a.equals("*") && token.contains(a))) {
                    specific.add(rule);
                    sawSpecific = true;
                } else if (groupAgents.contains("*")) {
                    general.add(rule);
                }
            }
            default -> {
            }
            }
        }
        return new RobotsTxt(sawSpecific ? specific : general);
    }

    private static Pattern toPattern(String value) {
        StringBuilder re = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == '*') {
                re.append(".*");
            } else if (c == '$') {
                re.append("$");
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString());
    }

    /** @param pathAndQuery e.g. {@code /a/b?x=1} */
    public boolean isAllowed(String pathAndQuery) {
        String path = pathAndQuery == null || pathAndQuery.isEmpty() ? "/" : pathAndQuery;
        Rule best = null;
        for (Rule r : rules) {
            if (r.pattern().matcher(path).lookingAt() && (best == null || r.length() > best.length()
                    || (r.length() == best.length() && r.allow()))) {
                best = r;
            }
        }
        return best == null || best.allow();
    }
}
