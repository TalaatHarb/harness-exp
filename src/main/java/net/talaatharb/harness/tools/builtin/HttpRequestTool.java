package net.talaatharb.harness.tools.builtin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import net.talaatharb.harness.tools.RiskLevel;
import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolArguments;
import net.talaatharb.harness.tools.ToolResult;

/** Explicitly approved HTTP API access, with no redirects, cookies or application-level retries. */
public class HttpRequestTool implements Tool {
    private static final Logger LOG = LoggerFactory.getLogger(HttpRequestTool.class);
    static final int MAX_REQUEST_BYTES = 1_000_000;
    static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private static final Set<String> BLOCKED_HEADERS = Set.of("host", "content-length", "connection", "upgrade",
            "expect", "transfer-encoding", "accept-encoding");
    private static final Pattern CHARSET = Pattern.compile("charset\\s*=\\s*\"?([\\w.:-]+)",
            Pattern.CASE_INSENSITIVE);
    private final HttpClient client;
    private final UnaryOperator<String> environment;

    @Inject
    public HttpRequestTool() {
        this(System::getenv);
    }

    HttpRequestTool(UnaryOperator<String> environment) {
        this.environment = environment;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public String name() { return "http_request"; }
    @Override public RiskLevel risk() { return RiskLevel.SENSITIVE; }
    @Override public boolean alwaysRequiresApproval() { return true; }
    @Override public String description() {
        return "Makes one HTTP(S) API request, including localhost/private APIs. ALWAYS requires user approval: "
                + "can send data or change remote state, even with GET. Never follows redirects or retries a result. "
                + "Returns status, selected response headers and bounded text (not binary). Body is UTF-8, max 1 MB. "
                + "Use bearerTokenEnv to reference an environment variable without putting the token in arguments. "
                + "Do not blindly retry mutations after timeouts: the server may already have applied them. "
                + "For web research prefer web_search_extract, which applies robots.txt and host restrictions.";
    }
    @Override public String argumentsHint() {
        return "{\"url\":\"https://api.example.com/items\",\"method\":\"GET\","
                + "\"headers\":{\"Accept\":\"application/json\"},\"body\":\"optional UTF-8 string\","
                + "\"bearerTokenEnv\":\"optional environment variable name\","
                + "\"timeoutSeconds\":30,\"maxResponseBytes\":16384} "
                + "Methods: GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS. Timeout 1-120s; response max 65536 bytes. "
                + "Omit unused fields; null, blank or 'default' optional controls use defaults. "
                + "GET/HEAD ignore empty bodies but reject nonempty bodies. Redirects and non-2xx statuses are errors.";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) throws IOException, InterruptedException {
        try {
            if (arguments == null) {
                throw new IllegalArgumentException("Arguments are required");
            }
            URI uri = uri(ToolArguments.string(arguments, "url", null).strip());
            if (!Set.of("http", "https").contains(uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getPort() > 65535 || uri.getPort() == 0) {
                throw new IllegalArgumentException("URL must be HTTP(S), with a host and without userinfo or a fragment");
            }
            String method = ToolArguments.string(arguments, "method", "GET").strip().toUpperCase(Locale.ROOT);
            if (!METHODS.contains(method)) {
                throw new IllegalArgumentException("Unsupported HTTP method");
            }
            int timeout = ToolArguments.integer(arguments, "timeoutSeconds", 30, 120);
            int maxBytes = ToolArguments.integer(arguments, "maxResponseBytes", 16 * 1024, MAX_RESPONSE_BYTES);
            Object rawBody = arguments.get("body");
            if (rawBody != null && !(rawBody instanceof String)) {
                throw new IllegalArgumentException("body must be a string");
            }
            String body = rawBody == null ? "" : (String) rawBody;
            if (body.length() > MAX_REQUEST_BYTES || body.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) {
                throw new IllegalArgumentException("Request body exceeds 1 MB");
            }
            if (method.equals("GET") || method.equals("HEAD")) {
                if (!ToolArguments.omitted(rawBody)) {
                    throw new IllegalArgumentException("GET/HEAD requests cannot have a nonempty body; omit 'body'");
                }
                rawBody = null;
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeout));
            Object rawHeaders = arguments.get("headers");
            if (!ToolArguments.omitted(rawHeaders)) {
                if (!(rawHeaders instanceof Map<?, ?> headers) || headers.size() > 32) {
                    throw new IllegalArgumentException("headers must be an object with at most 32 string values");
                }
                for (var entry : headers.entrySet()) {
                    if (entry.getValue() == null) {
                        continue;
                    }
                    if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof String value)
                            || BLOCKED_HEADERS.contains(name.toLowerCase(Locale.ROOT))
                            || name.length() > 128 || value.length() > 8192) {
                        throw new IllegalArgumentException("Invalid, oversized or restricted request header");
                    }
                    header(request, name, value);
                }
            }
            String token = null;
            if (!ToolArguments.omitted(arguments.get("bearerTokenEnv"))) {
                String variable = ToolArguments.string(arguments, "bearerTokenEnv", null).strip();
                if (!variable.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new IllegalArgumentException("bearerTokenEnv must name an environment variable");
                }
                if (request.build().headers().firstValue("Authorization").isPresent()) {
                    throw new IllegalArgumentException("Use either Authorization or bearerTokenEnv, not both");
                }
                token = environment.apply(variable);
                if (token == null || token.isBlank()) {
                    throw new IllegalArgumentException("Bearer token environment variable is missing or empty");
                }
                if (token.contains("\r") || token.contains("\n")) {
                    throw new IllegalArgumentException("Bearer token must not contain line breaks");
                }
                header(request, "Authorization", "Bearer " + token);
            }
            request.method(method, rawBody == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            var pending = client.sendAsync(request.build(), info -> new LimitedBody(maxBytes));
            HttpResponse<byte[]> response;
            try {
                response = pending.get(timeout, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                LOG.warn("HTTP request timed out; remote state is unknown");
                return ToolResult.error("HTTP request timed out; remote state is unknown. Do not blindly retry mutations.");
            } catch (ExecutionException e) {
                // Client exceptions can include URLs or header values; do not echo them into the transcript.
                String reason = transportFailure(e.getCause());
                LOG.warn("HTTP transport failed: {}", reason);
                return ToolResult.error("HTTP request failed: " + reason
                        + ". Remote state may be unknown; do not blindly retry mutations.");
            } finally {
                pending.cancel(true);
            }
            Map<String, String> headers = new LinkedHashMap<>();
            for (String name : List.of("content-type", "location", "retry-after", "etag", "last-modified")) {
                response.headers().firstValue(name).ifPresent(value -> headers.put(name, value));
            }
            String type = headers.getOrDefault("content-type", "text/plain");
            String mediaType = type.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
            boolean textual = method.equals("HEAD") || response.body().length == 0
                    || mediaType.startsWith("text/") || mediaType.equals("application/json")
                    || mediaType.equals("application/xml") || mediaType.equals("application/x-www-form-urlencoded")
                    || mediaType.endsWith("+json") || mediaType.endsWith("+xml");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", response.statusCode());
            result.put("headers", headers);
            result.put("truncated", response.body().length > maxBytes);
            if (textual) {
                var match = CHARSET.matcher(type);
                Charset charset;
                try {
                    charset = match.find() ? Charset.forName(match.group(1)) : StandardCharsets.UTF_8;
                } catch (IllegalArgumentException e) {
                    return ToolResult.error("HTTP " + response.statusCode() + ": unsupported response charset");
                }
                String text = new String(Arrays.copyOf(response.body(),
                        Math.min(response.body().length, maxBytes)), charset);
                result.put("body", token == null ? text : text.replace(token, "[REDACTED]"));
            } else {
                result.put("error", "Binary response is not supported by this text API tool");
            }
            if (token != null) {
                String secret = token;
                headers.replaceAll((name, value) -> value.replace(secret, "[REDACTED]"));
            }
            String output = new ObjectMapper().writeValueAsString(result);
            if (!textual || response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.warn("HTTP request returned status {} (text response: {})", response.statusCode(), textual);
            }
            return textual && response.statusCode() >= 200 && response.statusCode() < 300
                    ? ToolResult.ok(output) : ToolResult.error(output);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("HTTP arguments rejected: " + e.getMessage());
        }
    }

    static String transportFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof javax.net.ssl.SSLException) {
                return "TLS negotiation/certificate verification failed; check the server certificate and Java trust store";
            }
            if (cause instanceof java.nio.channels.UnresolvedAddressException
                    || cause instanceof java.net.UnknownHostException) {
                return "DNS lookup failed; check the hostname and network";
            }
            if (cause instanceof java.net.ConnectException) {
                return "Connection failed; check the host, port, firewall and whether the service is running";
            }
            if (cause instanceof java.net.http.HttpTimeoutException) {
                return "Request timed out; the server did not finish responding within the timeout";
            }
        }
        return "Response transport interrupted or invalid (" + failure.getClass().getSimpleName()
                + "); the server may have closed/reset the connection or sent an incomplete response";
    }

    private static URI uri(String url) {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid HTTP URL");
        }
    }

    private static void header(HttpRequest.Builder builder, String name, String value) {
        try {
            builder.header(name, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid HTTP header name/value (check for line breaks)");
        }
    }

    /** Captures at most limit+1 bytes (the extra byte detects truncation), then cancels the subscription. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                int count = Math.min(buffer.remaining(), limit + 1 - bytes.size());
                byte[] chunk = new byte[count];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
                if (bytes.size() > limit) {
                    // Cancellation can synchronously trigger onError (especially HTTP/2 stream resets).
                    result.complete(bytes.toByteArray());
                    subscription.cancel();
                    return;
                }
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
