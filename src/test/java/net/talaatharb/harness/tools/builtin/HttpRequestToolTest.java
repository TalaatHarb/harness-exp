package net.talaatharb.harness.tools.builtin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.talaatharb.harness.approval.ApprovalDecision;
import net.talaatharb.harness.approval.ApprovalPolicy;
import net.talaatharb.harness.approval.ApprovalService;
import net.talaatharb.harness.config.HarnessSettings;

class HttpRequestToolTest {
    private HttpServer server;
    private String base;
    private final HttpRequestTool tool = new HttpRequestTool(key -> key.equals("TEST_TOKEN") ? "secret-test-token" : null);
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private static void reply(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void sendsMethodHeadersBodyAndRedactsEnvironmentBearerToken() throws Exception {
        server.createContext("/echo", exchange -> reply(exchange, 201, "application/json",
                exchange.getRequestMethod() + " " + exchange.getRequestHeaders().getFirst("Authorization")
                + " " + exchange.getRequestHeaders().getFirst("X-Test") + " "
                + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        var result = tool.execute(Map.of("url", base + "/echo", "method", "POST", "body", "h\u00e9llo",
                "headers", Map.of("X-Test", "present"), "bearerTokenEnv", "TEST_TOKEN"));
        assertTrue(result.success(), result.output());
        var response = json.readTree(result.output());
        assertEquals(201, response.path("status").asInt());
        assertEquals("POST Bearer [REDACTED] present h\u00e9llo", response.path("body").asText());
        assertFalse(result.output().contains("secret-test-token"));
    }

    @Test
    void refusesRedirectsAndReturnsHttpFailureBodiesWithoutRetrying() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", base + "/target");
            reply(exchange, 302, "text/plain", "redirect");
        });
        server.createContext("/target", exchange -> {
            hits.incrementAndGet();
            reply(exchange, 200, "text/plain", "must not follow");
        });
        server.createContext("/failure", exchange -> {
            hits.incrementAndGet();
            exchange.getResponseHeaders().add("Set-Cookie", "private=value");
            reply(exchange, 429, "application/json", "{\"error\":\"slow down\"}");
        });
        var redirect = tool.execute(Map.of("url", base + "/redirect"));
        assertFalse(redirect.success());
        assertEquals(302, json.readTree(redirect.output()).path("status").asInt());
        assertEquals(0, hits.get());
        var failure = tool.execute(Map.of("url", base + "/failure"));
        assertFalse(failure.success());
        assertTrue(failure.output().contains("slow down"));
        assertFalse(failure.output().contains("private=value"));
        assertEquals(1, hits.get());
    }

    @Test
    void capsResponseBytesAndRejectsBinaryResponses() throws Exception {
        server.createContext("/large", exchange -> reply(exchange, 200, "text/plain", "x".repeat(10_000)));
        server.createContext("/binary", exchange -> reply(exchange, 200, "image/png", "binary"));
        var result = tool.execute(Map.of("url", base + "/large", "maxResponseBytes", 100));
        assertTrue(result.success(), result.output());
        var response = json.readTree(result.output());
        assertTrue(response.path("truncated").asBoolean());
        assertEquals(100, response.path("body").asText().length());
        var binary = tool.execute(Map.of("url", base + "/binary"));
        assertFalse(binary.success());
        assertTrue(binary.output().contains("Binary response"));
    }

    @Test
    void timesOutWhileReceivingTheBody() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/stall", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write('x');
                out.flush();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            var result = tool.execute(Map.of("url", base + "/stall", "timeoutSeconds", 1));
            assertFalse(result.success());
            assertTrue(result.output().contains("timed out") || result.output().contains("Timeout"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void headReturnsMetadataEvenForBinaryContentType() throws Exception {
        server.createContext("/head", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/pdf");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        var result = tool.execute(Map.of("url", base + "/head", "method", "HEAD"));
        assertTrue(result.success(), result.output());
        assertEquals("", json.readTree(result.output()).path("body").asText());
    }

    @Test
    void interruptionCancelsAnInProgressRequest() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/cancel", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write('x');
                out.flush();
                started.countDown();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        var outcome = new CompletableFuture<Throwable>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                tool.execute(Map.of("url", base + "/cancel"));
                outcome.complete(null);
            } catch (Exception e) {
                outcome.complete(e);
            }
        });
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            worker.interrupt();
            assertInstanceOf(InterruptedException.class, outcome.get(5, TimeUnit.SECONDS));
        } finally {
            worker.interrupt();
            release.countDown();
            worker.join(5_000);
        }
    }

    @Test
    void validatesBeforeSendingAndCannotBypassApproval() throws Exception {
        for (var args : java.util.List.of(
                Map.<String, Object>of("url", "file:///etc/passwd"),
                Map.<String, Object>of("url", "http://user:password@localhost/"),
                Map.<String, Object>of("url", base, "method", "CONNECT"),
                Map.<String, Object>of("url", base, "body", "GET body"),
                Map.<String, Object>of("url", base, "headers", Map.of("Host", "elsewhere")),
                Map.<String, Object>of("url", base, "headers", Map.of("X-Test", "bad\r\nInjected: secret")),
                Map.<String, Object>of("url", base, "timeoutSeconds", 121),
                Map.<String, Object>of("url", base, "bearerTokenEnv", "MISSING"),
                Map.<String, Object>of("url", base, "method", "POST", "body", "x".repeat(1_000_001)))) {
            assertFalse(tool.execute(args).success(), args.keySet().toString());
        }
        ApprovalService approvals = new ApprovalService(r -> CompletableFuture.completedFuture(ApprovalDecision.APPROVED),
                null);
        HarnessSettings settings = new HarnessSettings();
        settings.approval.policy = ApprovalPolicy.NEVER;
        settings.approval.alwaysAllowedTools.add(tool.name());
        assertTrue(approvals.requiresApproval(tool, settings));
        assertFalse(approvals.requestFor(tool, "{}", settings).allowAlways());
        assertFalse(approvals.requestFor(tool, "{}", settings).reason().contains("agent-written code"));
    }
}
