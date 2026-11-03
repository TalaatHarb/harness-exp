package net.talaatharb.harness.tools.mcp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Minimal stdio MCP server used by {@link McpTest}: tools {@code echo}, {@code fail} and {@code env}. */
public final class FakeMcpServer {
    private static final ObjectMapper JSON = new ObjectMapper();

    private FakeMcpServer() {
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        System.err.println("fake server starting");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            JsonNode response = handle(JSON.readTree(line));
            if (response != null) {
                out.println(JSON.writeValueAsString(response));
            }
        }
    }

    static JsonNode handle(JsonNode request) {
        if (!request.has("id")) {
            return null;
        }
        ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", request.get("id"));
        ObjectNode result = JSON.createObjectNode();
        switch (request.path("method").asText()) {
            case "initialize" -> {
                result.put("protocolVersion", request.path("params").path("protocolVersion").asText());
                result.putObject("capabilities").putObject("tools");
                result.putObject("serverInfo").put("name", "fake").put("version", "1.0");
            }
            case "tools/list" -> {
                var tools = result.putArray("tools");
                ObjectNode echo = tools.addObject().put("name", "echo").put("description", "Echoes text");
                echo.putObject("inputSchema").put("type", "object").putObject("properties").putObject("text")
                        .put("type", "string");
                echo.putObject("annotations").put("readOnlyHint", true);
                tools.addObject().put("name", "fail").put("description", "Always fails")
                        .putObject("annotations").put("destructiveHint", true);
                tools.addObject().put("name", "env").put("description", "Reads FAKE_MCP_VALUE");
            }
            case "tools/call" -> {
                JsonNode params = request.path("params");
                String name = params.path("name").asText();
                String text = switch (name) {
                    case "echo" -> "echo: " + params.path("arguments").path("text").asText();
                    case "fail" -> "boom";
                    case "env" -> String.valueOf(System.getenv("FAKE_MCP_VALUE"));
                    default -> null;
                };
                if (text == null) {
                    response.putObject("error").put("code", -32602).put("message", "Unknown tool " + name);
                    return response;
                }
                result.putArray("content").addObject().put("type", "text").put("text", text);
                result.put("isError", "fail".equals(name));
            }
            default -> {
                response.putObject("error").put("code", -32601).put("message", "Method not found");
                return response;
            }
        }
        response.set("result", result);
        return response;
    }
}
