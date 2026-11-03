package net.talaatharb.harness.tools.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * MCP "streamable HTTP" transport: every JSON-RPC message is POSTed to one endpoint; the server answers with JSON or
 * an SSE stream carrying the response. The {@code Mcp-Session-Id} from {@code initialize} is sent on later requests.
 */
public class HttpMcpTransport extends McpTransport {
    private static final Logger LOG = LoggerFactory.getLogger(HttpMcpTransport.class);
    static final String SESSION_HEADER = "Mcp-Session-Id";

    private final String serverName;
    private final URI endpoint;
    private final Map<String, String> headers;
    private final ExecutorService readers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mcp-http-reader");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient client;
    private volatile String sessionId;
    private volatile String protocolVersion;

    public HttpMcpTransport(String serverName, String url, Map<String, String> headers) throws IOException {
        this.serverName = serverName;
        try {
            this.endpoint = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid MCP server URL '" + url + "'", e);
        }
        if (endpoint.getScheme() == null || !endpoint.getScheme().startsWith("http")) {
            throw new IOException("MCP server URL must start with http:// or https://: " + url);
        }
        this.headers = Map.copyOf(headers);
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).executor(readers).build();
    }

    void protocolVersion(String version) {
        this.protocolVersion = version;
    }

    private HttpRequest.Builder base() {
        HttpRequest.Builder b = HttpRequest.newBuilder(endpoint);
        headers.forEach(b::header);
        if (sessionId != null) {
            b.header(SESSION_HEADER, sessionId);
        }
        if (protocolVersion != null) {
            b.header("MCP-Protocol-Version", protocolVersion);
        }
        return b;
    }

    @Override
    protected void send(ObjectNode message) throws IOException {
        if (!isOpen()) {
            throw new McpException("Connection closed: " + closedReason());
        }
        JsonNode id = message.get("id");
        boolean isRequest = id != null && message.has("method");
        HttpRequest request = base().header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(message), StandardCharsets.UTF_8))
                .build();
        if (!isRequest) {
            try {
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 400) {
                    LOG.debug("[{}] notification rejected with HTTP {}", serverName, response.statusCode());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
            return;
        }
        client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).whenComplete((response, error) -> {
            if (error != null) {
                fail(id, "Could not reach " + endpoint + ": " + rootMessage(error));
                return;
            }
            try {
                handle(response, id);
            } catch (IOException | RuntimeException e) {
                fail(id, "Bad response from " + endpoint + ": " + e.getMessage());
            }
        });
    }

    private void handle(HttpResponse<InputStream> response, JsonNode id) throws IOException {
        response.headers().firstValue(SESSION_HEADER).ifPresent(s -> sessionId = s);
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status >= 400) {
                String text = new String(body.readNBytes(2_000), StandardCharsets.UTF_8).strip();
                String hint = status == 401 || status == 403 ? " (check the Authorization header)"
                        : status == 404 && sessionId != null ? " (session expired; reconnect)" : "";
                fail(id, "HTTP " + status + hint + (text.isEmpty() ? "" : ": " + text));
                return;
            }
            String type = response.headers().firstValue("Content-Type").orElse("");
            if (type.contains("text/event-stream")) {
                readEvents(body);
            } else {
                byte[] bytes = body.readAllBytes();
                if (bytes.length == 0) {
                    fail(id, "Empty response (HTTP " + status + ")");
                    return;
                }
                JsonNode node = JSON.readTree(bytes);
                if (node.isArray()) {
                    node.forEach(this::receive);
                } else {
                    receive(node);
                }
            }
        }
    }

    /** Reads SSE events ({@code data:} lines, blank line terminated) until the server closes the stream. */
    private void readEvents(InputStream body) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isEmpty()) {
                dispatch(data);
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(line.substring(5).stripLeading());
            }
        }
        dispatch(data);
    }

    private void dispatch(StringBuilder data) {
        if (data.length() == 0) {
            return;
        }
        String text = data.toString();
        data.setLength(0);
        try {
            receive(JSON.readTree(text));
        } catch (IOException e) {
            LOG.warn("[{}] invalid SSE data: {}", serverName, text);
        }
    }

    private void fail(JsonNode id, String message) {
        ObjectNode error = JSON.createObjectNode().put("jsonrpc", "2.0");
        error.set("id", id);
        error.putObject("error").put("code", -32000).put("message", message);
        receive(error);
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    @Override
    public void close() {
        closed("closed by the harness");
        if (sessionId != null) {
            try {
                client.send(base().DELETE().timeout(Duration.ofSeconds(3)).build(),
                        HttpResponse.BodyHandlers.discarding());
            } catch (IOException | RuntimeException e) {
                LOG.debug("[{}] session delete failed", serverName, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        readers.shutdownNow();
    }
}
