package net.talaatharb.harness.tools.mcp;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON-RPC 2.0 plumbing shared by the MCP transports: request ids, pending responses, timeouts, cancellation and
 * answering the few requests a server may send to the client.
 */
public abstract class McpTransport implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(McpTransport.class);

    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private volatile Consumer<String> notificationHandler = method -> {
    };
    private volatile String closedReason;
    private volatile Consumer<String> closeHandler = reason -> {
    };

    /** Writes one JSON-RPC message to the server. */
    protected abstract void send(ObjectNode message) throws IOException;

    /** Called for each server→client notification method (e.g. {@code notifications/tools/list_changed}). */
    public void onNotification(Consumer<String> handler) {
        this.notificationHandler = handler;
    }

    /** Called once when the connection ends, e.g. the server process exits. */
    public void onClosed(Consumer<String> handler) {
        this.closeHandler = handler;
    }

    /** Sends a request and waits for its result; JSON-RPC errors become {@link McpException}. */
    public JsonNode request(String method, JsonNode params, Duration timeout)
            throws IOException, InterruptedException {
        if (closedReason != null) {
            throw new McpException("Connection closed: " + closedReason);
        }
        long id = ids.incrementAndGet();
        ObjectNode message = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            send(message);
            JsonNode response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            JsonNode error = response.get("error");
            if (error != null && !error.isNull()) {
                throw new McpException(method + " failed: " + error.path("message").asText(error.toString())
                        + " (code " + error.path("code").asText("?") + ")");
            }
            return response.path("result");
        } catch (TimeoutException e) {
            cancel(id, "timeout");
            throw new McpException(method + " timed out after " + timeout.toSeconds() + "s");
        } catch (InterruptedException e) {
            cancel(id, "cancelled by the user");
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof IOException io ? io : new IOException(cause.getMessage(), cause);
        } finally {
            pending.remove(id);
        }
    }

    public void notify(String method, JsonNode params) throws IOException {
        ObjectNode message = JSON.createObjectNode().put("jsonrpc", "2.0").put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        send(message);
    }

    private void cancel(long id, String reason) {
        try {
            notify("notifications/cancelled", JSON.createObjectNode().put("requestId", id).put("reason", reason));
        } catch (IOException | RuntimeException e) {
            LOG.debug("Could not send cancellation for request {}", id, e);
        }
    }

    /** Routes a message received from the server. */
    protected void receive(JsonNode message) {
        if (message == null || !message.isObject()) {
            return;
        }
        JsonNode id = message.get("id");
        String method = message.path("method").asText(null);
        if (method == null && id != null) {
            CompletableFuture<JsonNode> future = pending.get(id.asLong());
            if (future != null) {
                future.complete(message);
            }
        } else if (method != null && id != null) {
            answerServerRequest(id, method);
        } else if (method != null) {
            notificationHandler.accept(method);
        }
    }

    /** The harness doesn't offer sampling/roots/elicitation; it only answers pings. */
    private void answerServerRequest(JsonNode id, String method) {
        ObjectNode reply = JSON.createObjectNode().put("jsonrpc", "2.0");
        reply.set("id", id);
        if ("ping".equals(method)) {
            reply.set("result", JSON.createObjectNode());
        } else {
            reply.putObject("error").put("code", -32601).put("message", "Method not supported by the client: " + method);
        }
        try {
            send(reply);
        } catch (IOException e) {
            LOG.debug("Could not answer server request {}", method, e);
        }
    }

    /** Fails all waiting requests, e.g. when the server process exits. */
    protected void closed(String reason) {
        boolean first;
        synchronized (this) {
            first = closedReason == null;
            if (first) {
                closedReason = reason;
            }
        }
        McpException error = new McpException("Connection closed: " + reason);
        pending.values().forEach(f -> f.completeExceptionally(error));
        pending.clear();
        if (first) {
            closeHandler.accept(reason);
        }
    }

    public boolean isOpen() {
        return closedReason == null;
    }

    public String closedReason() {
        return closedReason;
    }

    @Override
    public abstract void close();

    /** A protocol-level failure (error response, timeout, closed connection). */
    public static class McpException extends IOException {
        private static final long serialVersionUID = 1L;

        public McpException(String message) {
            super(message);
        }
    }
}
