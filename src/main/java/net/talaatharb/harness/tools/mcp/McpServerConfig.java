package net.talaatharb.harness.tools.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** Configuration for a Model Context Protocol server. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class McpServerConfig {
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{(?:env:)?([A-Za-z_][A-Za-z0-9_]*)}");

    public enum Transport {
        STDIO, HTTP
    }

    public String name = "";
    public Transport transport = Transport.STDIO;
    /** Command line for {@link Transport#STDIO} servers, e.g. {@code npx -y @modelcontextprotocol/server-filesystem .}. */
    public String command = "";
    /** Endpoint for {@link Transport#HTTP} (streamable HTTP) servers. */
    public String url = "";
    public boolean enabled = true;
    /**
     * Environment variables for stdio servers, or HTTP headers for http servers. Values may reference the harness'
     * environment as {@code ${VAR}} so secrets don't have to be stored in settings.json.
     */
    public Map<String, String> env = new LinkedHashMap<>();

    public McpServerConfig() {
    }

    public McpServerConfig(String name, Transport transport, String command, String url, boolean enabled) {
        this(name, transport, command, url, enabled, Map.of());
    }

    public McpServerConfig(String name, Transport transport, String command, String url, boolean enabled,
            Map<String, String> env) {
        this.name = name;
        this.transport = transport;
        this.command = command;
        this.url = url;
        this.enabled = enabled;
        this.env = new LinkedHashMap<>(env == null ? Map.of() : env);
    }

    public McpServerConfig copy() {
        return new McpServerConfig(name, transport, command, url, enabled, env);
    }

    public String endpoint() {
        return transport == Transport.HTTP ? url : command;
    }

    /** Identity of the connection: changing any of these needs a reconnect. */
    @JsonIgnore
    public String connectionKey() {
        return name + "\u0000" + transport + "\u0000" + endpoint() + "\u0000" + env;
    }

    /** {@code env} with {@code ${VAR}} references replaced from the given lookup (unknown variables become empty). */
    public Map<String, String> resolvedEnv(UnaryOperator<String> lookup) {
        Map<String, String> resolved = new LinkedHashMap<>();
        env.forEach((k, v) -> resolved.put(k, expand(v, lookup)));
        return resolved;
    }

    static String expand(String value, UnaryOperator<String> lookup) {
        if (value == null) {
            return "";
        }
        Matcher m = VARIABLE.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(Objects.requireNonNullElse(lookup.apply(m.group(1)), "")));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Whether a value is only a {@code ${VAR}} reference (not a secret in itself). */
    public static boolean isReference(String value) {
        return value != null && VARIABLE.matcher(value.strip()).matches();
    }

    /** Formats {@code env} as {@code KEY=VALUE; KEY2=VALUE2} for a one-line text field. */
    public static String formatEnv(Map<String, String> env) {
        List<String> parts = new ArrayList<>();
        env.forEach((k, v) -> parts.add(k + "=" + v));
        return String.join("; ", parts);
    }

    /** Parses {@code KEY=VALUE; KEY2=VALUE2} (also newline separated). */
    public static Map<String, String> parseEnv(String text) {
        Map<String, String> env = new LinkedHashMap<>();
        if (text == null) {
            return env;
        }
        for (String part : text.split("[;\\n]")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                env.put(part.substring(0, eq).strip(), part.substring(eq + 1).strip());
            } else if (part.indexOf(':') > 0) {
                int colon = part.indexOf(':');
                env.put(part.substring(0, colon).strip(), part.substring(colon + 1).strip());
            }
        }
        return env;
    }

    /**
     * Splits a command line into arguments, honouring double and single quotes, e.g.
     * {@code npx -y "@scope/server" "C:\My Files"}.
     */
    public static List<String> tokenize(String commandLine) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inToken = false;
        char quote = 0;
        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    args.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
            } else {
                current.append(c);
                inToken = true;
            }
        }
        if (inToken) {
            args.add(current.toString());
        }
        return args;
    }

    /**
     * Reads servers from an {@code mcp.json}-style document as used by Claude Desktop ({@code "mcpServers"}), VS Code
     * ({@code "servers"}) and others: {@code {"name": {"command": "npx", "args": [...], "env": {...}}}} or
     * {@code {"name": {"url": "https://…", "headers": {...}}}}.
     */
    public static List<McpServerConfig> fromMcpJson(JsonNode root) {
        JsonNode servers = root.has("mcpServers") ? root.get("mcpServers")
                : root.has("servers") ? root.get("servers") : root;
        List<McpServerConfig> result = new ArrayList<>();
        if (servers == null || !servers.isObject()) {
            return result;
        }
        servers.fields().forEachRemaining(field -> {
            JsonNode s = field.getValue();
            if (!s.isObject()) {
                return;
            }
            McpServerConfig c = new McpServerConfig();
            c.name = field.getKey();
            c.enabled = !s.path("disabled").asBoolean(false);
            if (s.hasNonNull("url") || s.hasNonNull("serverUrl")) {
                c.transport = Transport.HTTP;
                c.url = s.hasNonNull("url") ? s.get("url").asText() : s.get("serverUrl").asText();
                s.path("headers").fields().forEachRemaining(h -> c.env.put(h.getKey(), h.getValue().asText()));
            } else {
                c.transport = Transport.STDIO;
                if (s.path("command").asText("").isBlank()) {
                    return;
                }
                List<String> args = new ArrayList<>();
                args.add(s.path("command").asText(""));
                s.path("args").forEach(a -> args.add(a.asText()));
                c.command = join(args);
                s.path("env").fields().forEachRemaining(e -> c.env.put(e.getKey(), e.getValue().asText()));
            }
            if (!c.endpoint().isBlank()) {
                result.add(c);
            }
        });
        return result;
    }

    /** Joins arguments into a command line, quoting those with spaces. */
    public static String join(List<String> args) {
        List<String> parts = new ArrayList<>();
        for (String a : args) {
            parts.add(a.isEmpty() || a.chars().anyMatch(Character::isWhitespace) ? "\"" + a + "\"" : a);
        }
        return String.join(" ", parts);
    }
}
