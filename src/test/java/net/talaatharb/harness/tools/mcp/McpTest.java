package net.talaatharb.harness.tools.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.config.SettingsStore;
import net.talaatharb.harness.tools.RiskLevel;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolResult;

class McpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @TempDir
    Path dir;

    /** Command line that runs {@link FakeMcpServer} in a separate JVM. */
    private static String fakeServerCommand() {
        String java = Path.of(System.getProperty("java.home"), "bin",
                File.separatorChar == '\\' ? "java.exe" : "java").toString();
        String classpath = Stream.of(FakeMcpServer.class, ObjectMapper.class, JsonFactory.class, JsonProperty.class)
                .map(c -> {
                    try {
                        return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).distinct().collect(Collectors.joining(File.pathSeparator));
        return McpServerConfig.join(List.of(java, "-cp", classpath, FakeMcpServer.class.getName()));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(50);
        }
    }

    @Test
    void tokenizesAndJoinsCommandLines() {
        List<String> args = McpServerConfig.tokenize("npx -y \"@scope/server x\" 'C:\\My Files' plain");
        assertEquals(List.of("npx", "-y", "@scope/server x", "C:\\My Files", "plain"), args);
        assertEquals(args, McpServerConfig.tokenize(McpServerConfig.join(args)));
        assertEquals(List.of("a", ""), McpServerConfig.tokenize("a \"\""));
    }

    @Test
    void parsesFormatsAndExpandsEnv() {
        Map<String, String> env = McpServerConfig.parseEnv("A=1; B = x=y\nAuthorization: Bearer ${TOKEN}");
        assertEquals(Map.of("A", "1", "B", "x=y", "Authorization", "Bearer ${TOKEN}"), env);
        assertEquals("A=1; B=x=y; Authorization=Bearer ${TOKEN}", McpServerConfig.formatEnv(env));
        McpServerConfig c = new McpServerConfig("s", McpServerConfig.Transport.HTTP, "", "http://x", true, env);
        Map<String, String> resolved = c.resolvedEnv(Map.of("TOKEN", "abc")::get);
        assertEquals("Bearer abc", resolved.get("Authorization"));
        assertEquals("", McpServerConfig.expand("${env:MISSING}", k -> null));
        assertTrue(McpServerConfig.isReference(" ${env:GITHUB_TOKEN} "));
        assertFalse(McpServerConfig.isReference("Bearer ${TOKEN}"));
    }

    @Test
    void readsClaudeAndVsCodeMcpJson() throws Exception {
        List<McpServerConfig> claude = McpServerConfig.fromMcpJson(JSON.readTree("""
                {"mcpServers": {
                  "fs": {"command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "C:\\\\My Docs"],
                         "env": {"DEBUG": "1"}},
                  "off": {"command": "uvx", "args": ["x"], "disabled": true},
                  "broken": {"args": []}
                }}"""));
        assertEquals(2, claude.size());
        McpServerConfig fs = claude.get(0);
        assertEquals(McpServerConfig.Transport.STDIO, fs.transport);
        assertEquals(List.of("npx", "-y", "@modelcontextprotocol/server-filesystem", "C:\\My Docs"),
                McpServerConfig.tokenize(fs.command));
        assertEquals(Map.of("DEBUG", "1"), fs.env);
        assertFalse(claude.get(1).enabled);

        List<McpServerConfig> vscode = McpServerConfig.fromMcpJson(JSON.readTree("""
                {"servers": {"gh": {"type": "http", "url": "https://api.example.com/mcp",
                                    "headers": {"Authorization": "Bearer ${env:GH}"}}}}"""));
        assertEquals(1, vscode.size());
        assertEquals(McpServerConfig.Transport.HTTP, vscode.get(0).transport);
        assertEquals("https://api.example.com/mcp", vscode.get(0).url);
        assertEquals("Bearer ${env:GH}", vscode.get(0).env.get("Authorization"));
    }

    @Test
    void wrapsShimsInCmdOnWindowsOnly() {
        assertEquals(List.of("cmd.exe", "/c", "npx", "-y", "pkg"), StdioMcpTransport.command("npx -y pkg", true));
        assertEquals(List.of("C:\\x\\server.exe", "a"), StdioMcpTransport.command("C:\\x\\server.exe a", true));
        assertEquals(List.of("npx", "-y", "pkg"), StdioMcpTransport.command("npx -y pkg", false));
        assertEquals(List.of(), StdioMcpTransport.command("  ", true));
    }

    @Test
    void flattensContentBlocks() throws Exception {
        String text = McpClient.formatContent(JSON.readTree("""
                {"content": [
                  {"type": "text", "text": "hello"},
                  {"type": "image", "mimeType": "image/png", "data": "AAAA"},
                  {"type": "resource", "resource": {"uri": "file:///a.txt", "text": "body"}},
                  {"type": "resource_link", "uri": "file:///b", "name": "b"}
                ]}"""));
        assertEquals("hello\n[image: image/png, 3 bytes; not shown to the model]\n[resource file:///a.txt]\nbody\n"
                + "[resource link: file:///b (b)]", text);
        assertEquals("{\"x\":1}", McpClient.formatContent(JSON.readTree("{\"content\":[],\"structuredContent\":{\"x\":1}}")));
        assertEquals("(no output)", McpClient.formatContent(JSON.readTree("{}")));
    }

    @Test
    void capturesBinaryResultsFromConnectedMcpToolsWithoutPassingPayloadToModel() throws Exception {
        var artifacts = new net.talaatharb.harness.history.ArtifactStore(dir.resolve("history.db"));
        SettingsStore store = new SettingsStore(dir.resolve("settings.json"));
        HarnessSettings settings = store.copy();
        settings.tools.toolsEnabled = true;
        settings.tools.mcpServers = new ArrayList<>(List.of(
                new McpServerConfig("media", McpServerConfig.Transport.STDIO, fakeServerCommand(), "", true)));
        store.replace(settings);
        String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j4WQAAAAASUVORK5CYII=";
        byte[] wav = net.talaatharb.harness.speech.WavIO.toWavBytes(
                new net.talaatharb.harness.speech.AudioClip(new float[] { 0, 0.5f }, 16000));
        var content = JSON.createArrayNode();
        content.addObject().put("type", "text").put("text", "Created media");
        content.addObject().put("type", "image").put("mimeType", "image/png").put("data", png);
        content.addObject().put("type", "audio").put("mimeType", "audio/wav")
                .put("data", java.util.Base64.getEncoder().encodeToString(wav));
        content.addObject().put("type", "resource").putObject("resource")
                .put("uri", "file:///server/custom.bin").put("mimeType", "application/octet-stream").put("blob", "AP+A");
        try (McpClientManager manager = new McpClientManager(store, k -> null, artifacts)) {
            await(() -> manager.status(settings.tools.mcpServers.get(0)).startsWith("connected"));
            var echo = manager.tools(settings.tools.mcpServers).get(0);
            String previous = net.talaatharb.harness.agent.ConversationScope.enter("conversation");
            try {
                ToolResult result = echo.execute(Map.of("content", content));
                assertTrue(result.success(), result.output());
                assertTrue(result.output().startsWith("Created media\n"));
                assertFalse(result.output().contains(png));
                List<net.talaatharb.harness.history.ArtifactStore.Artifact> files = new ArrayList<>();
                for (String line : result.output().lines().toList()) {
                    if (line.startsWith(net.talaatharb.harness.history.ArtifactStore.ARTIFACT_PREFIX)) {
                        files.add(JSON.readValue(line.substring(net.talaatharb.harness.history.ArtifactStore.ARTIFACT_PREFIX.length()),
                                net.talaatharb.harness.history.ArtifactStore.Artifact.class));
                    }
                }
                assertEquals(List.of("mcp-image-2.png", "mcp-audio-3.wav", "mcp-resource-4.bin"),
                        files.stream().map(net.talaatharb.harness.history.ArtifactStore.Artifact::name).toList());
                assertTrue(new net.talaatharb.harness.history.ArtifactStore(dir.resolve("history.db"))
                        .file("conversation", files.get(0)).toFile().isFile());
                assertTrue(java.util.Arrays.equals(java.util.Base64.getDecoder().decode(png),
                        java.nio.file.Files.readAllBytes(artifacts.file("conversation", files.get(0)))));
                assertTrue(java.util.Arrays.equals(wav, java.nio.file.Files.readAllBytes(artifacts.file("conversation", files.get(1)))));
                assertTrue(java.util.Arrays.equals(new byte[] { 0, (byte) 255, (byte) 128 },
                        java.nio.file.Files.readAllBytes(artifacts.file("conversation", files.get(2)))));
                ToolResult failed = manager.tools(settings.tools.mcpServers).get(1).execute(Map.of("content", content));
                assertFalse(failed.success());
                assertFalse(failed.output().contains(net.talaatharb.harness.history.ArtifactStore.ARTIFACT_PREFIX));
                Path owned = artifacts.file("conversation", files.get(0));
                artifacts.deleteConversation("conversation");
                assertFalse(java.nio.file.Files.exists(owned));
            } finally {
                net.talaatharb.harness.agent.ConversationScope.restore(previous);
            }
        }
    }

    @Test
    void reportsMalformedMcpBinaryDataAndMissingConversation() throws Exception {
        var artifacts = new net.talaatharb.harness.history.ArtifactStore(dir.resolve("history.db"));
        JsonNode invalid = JSON.readTree("""
                {"content":[{"type":"image","mimeType":"image/png","data":"not base64!"}]}
                """);
        assertThrows(IOException.class, () -> McpClient.formatContent(invalid, artifacts, "conversation"));
        JsonNode missing = JSON.readTree("""
                {"content":[{"type":"audio","mimeType":"audio/wav"}]}
                """);
        assertThrows(IOException.class, () -> McpClient.formatContent(missing, artifacts, "conversation"));
        JsonNode valid = JSON.readTree("""
                {"content":[{"type":"image","mimeType":"image/png","data":"AAAA"}]}
                """);
        assertThrows(IOException.class, () -> McpClient.formatContent(valid, artifacts, null));
        String link = McpClient.formatContent(JSON.readTree("""
                {"content":[{"type":"resource_link","uri":"https://example.com/audio.wav"}]}
                """), artifacts, "conversation");
        assertEquals("[resource link: https://example.com/audio.wav]", link);
        assertFalse(java.nio.file.Files.exists(dir.resolve("history.db.artifacts")));
    }

    @Test
    void talksToAStdioServer() throws Exception {
        McpServerConfig config = new McpServerConfig("fake", McpServerConfig.Transport.STDIO, fakeServerCommand(), "",
                true, Map.of("FAKE_MCP_VALUE", "${SECRET}"));
        try (McpClient client = McpClient.connect(config, Map.of("SECRET", "s3cret")::get, TIMEOUT)) {
            assertEquals("fake 1.0", client.serverInfo());
            List<McpClient.ToolInfo> tools = client.listTools();
            assertEquals(List.of("echo", "fail", "env"), tools.stream().map(McpClient.ToolInfo::name).toList());

            McpTool echo = new McpTool("echo", client, tools.get(0), () -> TIMEOUT);
            assertEquals(RiskLevel.SAFE, echo.risk());
            assertEquals("mcp:fake", echo.source());
            assertTrue(echo.argumentsHint().contains("\"text\""));
            ToolResult ok = echo.execute(Map.of("text", "hi"));
            assertTrue(ok.success());
            assertEquals("echo: hi", ok.output());
            assertEquals("echo: default", echo.execute(Map.of("text", "default")).output());
            var nullable = net.talaatharb.harness.tools.ToolCallParser.parse(
                    "{\"name\":\"echo\",\"arguments\":{\"text\":null}}").orElseThrow();
            assertTrue(echo.execute(nullable.arguments()).success());
            assertTrue(echo.execute(null).success());

            McpTool fail = new McpTool("fail", client, tools.get(1), () -> TIMEOUT);
            assertEquals(RiskLevel.DANGEROUS, fail.risk());
            ToolResult failed = fail.execute(Map.of());
            assertFalse(failed.success());
            assertEquals("boom", failed.output());

            assertEquals(RiskLevel.SENSITIVE, new McpTool("env", client, tools.get(2), () -> TIMEOUT).risk());
            assertEquals("s3cret", client.callTool("env", Map.of(), TIMEOUT).output());

            McpTransport.McpException unknown = assertThrows(McpTransport.McpException.class,
                    () -> client.callTool("nope", Map.of(), TIMEOUT));
            assertTrue(unknown.getMessage().contains("Unknown tool nope"), unknown.getMessage());
        }
    }

    @Test
    void reportsAServerThatCannotStart() {
        McpServerConfig config = new McpServerConfig("missing", McpServerConfig.Transport.STDIO,
                "definitely-not-a-real-mcp-server-xyz", "", true);
        assertThrows(IOException.class, () -> McpClient.connect(config, k -> null, Duration.ofSeconds(10)));
    }

    @Test
    void talksToAStreamableHttpServer() throws Exception {
        List<String> sessions = new CopyOnWriteArrayList<>();
        List<String> methods = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            try {
                if ("DELETE".equals(exchange.getRequestMethod())) {
                    methods.add("DELETE");
                    reply(exchange, 200, "application/json", "");
                    return;
                }
                if (!"Bearer t0k".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    reply(exchange, 401, "text/plain", "unauthorized");
                    return;
                }
                JsonNode request = JSON.readTree(exchange.getRequestBody());
                String method = request.path("method").asText();
                methods.add(method);
                String session = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
                if (!"initialize".equals(method)) {
                    sessions.add(String.valueOf(session));
                }
                JsonNode response = FakeMcpServer.handle(request);
                if (response == null) {
                    reply(exchange, 202, "application/json", "");
                } else if ("tools/list".equals(method)) {
                    reply(exchange, 200, "text/event-stream", "event: message\ndata: "
                            + JSON.writeValueAsString(response) + "\n\n");
                } else {
                    if ("initialize".equals(method)) {
                        exchange.getResponseHeaders().add("Mcp-Session-Id", "session-1");
                    }
                    reply(exchange, 200, "application/json", JSON.writeValueAsString(response));
                }
            } catch (RuntimeException e) {
                reply(exchange, 500, "text/plain", e.toString());
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
            McpServerConfig config = new McpServerConfig("web", McpServerConfig.Transport.HTTP, "", url, true,
                    Map.of("Authorization", "Bearer ${TOKEN}"));
            try (McpClient client = McpClient.connect(config, Map.of("TOKEN", "t0k")::get, TIMEOUT)) {
                assertEquals(3, client.listTools().size());
                assertEquals("echo: web", client.callTool("echo", Map.of("text", "web"), TIMEOUT).output());
            }
            assertTrue(sessions.stream().allMatch("session-1"::equals), sessions.toString());
            assertTrue(methods.contains("notifications/initialized"), methods.toString());
            assertTrue(methods.contains("DELETE"), methods.toString());

            IOException denied = assertThrows(IOException.class,
                    () -> McpClient.connect(config, k -> "wrong", TIMEOUT));
            assertTrue(denied.getMessage().contains("401"), denied.getMessage());
        } finally {
            server.stop(0);
        }
    }

    private static void reply(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void managerConnectsServersAndKeepsToolNamesUnique() throws Exception {
        SettingsStore store = new SettingsStore(dir.resolve("settings.json"));
        HarnessSettings s = store.copy();
        s.tools.toolsEnabled = true;
        s.tools.mcpServers = new ArrayList<>(List.of(
                new McpServerConfig("one", McpServerConfig.Transport.STDIO, fakeServerCommand(), "", true),
                new McpServerConfig("two", McpServerConfig.Transport.STDIO, fakeServerCommand(), "", true),
                new McpServerConfig("off", McpServerConfig.Transport.STDIO, fakeServerCommand(), "", false)));
        store.replace(s);
        List<String> events = new CopyOnWriteArrayList<>();
        try (McpClientManager manager = new McpClientManager(store, k -> null)) {
            manager.addListener(() -> events.add("x"));
            List<McpServerConfig> servers = store.current().tools.mcpServers;
            await(() -> servers.stream().limit(2).allMatch(c -> manager.status(c).startsWith("connected")));
            assertEquals("connected · 3 tool(s) · fake 1.0", manager.status(servers.get(0)));
            assertEquals("disabled", manager.status(servers.get(2)));

            List<Tool> tools = manager.tools(servers);
            assertEquals(List.of("echo", "fail", "env", "two_echo", "two_fail", "two_env"),
                    tools.stream().map(Tool::name).toList());
            assertEquals("echo: dup", tools.get(3).execute(Map.of("text", "dup")).output());

            McpServerConfig changed = servers.get(0).copy();
            changed.command = changed.command + " extra";
            assertEquals("not connected (apply to connect)", manager.status(changed));

            HarnessSettings off = store.copy();
            off.tools.toolsEnabled = false;
            store.replace(off);
            assertTrue(manager.tools(servers).isEmpty());
            assertEquals("tool calling is off", manager.status(servers.get(0)));
            assertFalse(events.isEmpty());

            McpServerConfig bad = new McpServerConfig("bad", McpServerConfig.Transport.STDIO,
                    "definitely-not-a-real-mcp-server-xyz", "", true);
            assertThrows(Exception.class, () -> manager.test(bad));
            assertEquals(3, manager.test(servers.get(0)).size());
        }
    }
}
