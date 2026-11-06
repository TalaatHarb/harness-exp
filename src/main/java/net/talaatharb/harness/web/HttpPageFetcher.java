package net.talaatharb.harness.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polite page download: identifies itself, obeys robots.txt, spaces requests to the same host, refuses private network
 * addresses by default and gives up on 401/403/429/captcha-style responses instead of trying to get around them.
 */
@com.google.inject.Singleton
public class HttpPageFetcher implements PageFetcher {
    private static final Logger LOG = LoggerFactory.getLogger(HttpPageFetcher.class);
    private static final int MAX_REDIRECTS = 5;
    private static final Pattern CHARSET = Pattern.compile("charset=\"?([\\w.:-]+)", Pattern.CASE_INSENSITIVE);

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, RobotsTxt> robots = new ConcurrentHashMap<>();
    private final Map<String, Long> lastRequest = new ConcurrentHashMap<>();

    @Override
    public FetchedPage fetch(String url, WebSearchSettings settings) throws IOException {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URI uri = validate(current, settings);
            if (settings.respectRobotsTxt && !robotsFor(uri, settings).isAllowed(pathAndQuery(uri))) {
                throw new IOException("Blocked by robots.txt: " + current);
            }
            HttpResponse<InputStream> response = get(uri, settings);
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                response.body().close();
                String location = response.headers().firstValue("Location").orElse("");
                if (location.isBlank()) {
                    throw new IOException("Redirect without Location from " + current);
                }
                current = uri.resolve(location).toString();
                continue;
            }
            try (InputStream body = response.body()) {
                if (status == 401 || status == 403 || status == 429 || status == 451) {
                    throw new IOException("Access refused (HTTP " + status + "): " + current);
                }
                if (status < 200 || status >= 300) {
                    throw new IOException("HTTP " + status + " from " + current);
                }
                String type = response.headers().firstValue("Content-Type").orElse("text/html");
                String lower = type.toLowerCase(Locale.ROOT);
                if (!lower.contains("html") && !lower.contains("xml") && !lower.startsWith("text/plain")) {
                    throw new IOException("Not an HTML page (" + type + "): " + current);
                }
                byte[] bytes = readLimited(body, settings.maxResponseBytes);
                String html = new String(bytes, charset(type));
                if (looksLikeChallenge(html)) {
                    throw new IOException("Anti-bot challenge page returned by " + current);
                }
                return new FetchedPage(current, html, type);
            }
        }
        throw new IOException("Too many redirects: " + url);
    }

    private HttpResponse<InputStream> get(URI uri, WebSearchSettings settings) throws IOException {
        pace(uri.getHost(), settings.minHostDelayMillis);
        HttpRequest request = HttpRequest.newBuilder(uri).GET()
                .timeout(Duration.ofSeconds(settings.fetchTimeoutSeconds))
                .header("User-Agent", settings.userAgent)
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,text/plain;q=0.5")
                .header("Accept-Language", "en").build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + uri, e);
        }
    }

    private RobotsTxt robotsFor(URI uri, WebSearchSettings settings) {
        String origin = uri.getScheme() + "://" + uri.getAuthority();
        return robots.computeIfAbsent(origin, o -> {
            try {
                URI robotsUri = URI.create(o + "/robots.txt");
                HttpResponse<InputStream> r = get(robotsUri, settings);
                try (InputStream in = r.body()) {
                    int status = r.statusCode();
                    if (status >= 200 && status < 300) {
                        String body = new String(readLimited(in, 500_000), StandardCharsets.UTF_8);
                        return RobotsTxt.parse(body, productToken(settings.userAgent));
                    }
                    // RFC 9309: 4xx means no restrictions, anything else (unreachable) means assume complete disallow.
                    return status >= 400 && status < 500 ? RobotsTxt.ALLOW_ALL : RobotsTxt.DISALLOW_ALL;
                }
            } catch (IOException | RuntimeException e) {
                LOG.debug("robots.txt of {} unavailable: {}", o, e.toString());
                return RobotsTxt.DISALLOW_ALL;
            }
        });
    }

    private void pace(String host, int delayMillis) throws IOException {
        if (delayMillis <= 0) {
            return;
        }
        long wait;
        synchronized (lastRequest) {
            long now = System.currentTimeMillis();
            long next = Math.max(now, lastRequest.getOrDefault(host, 0L) + delayMillis);
            lastRequest.put(host, next);
            wait = next - now;
        }
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for " + host, e);
            }
        }
    }

    static URI validate(String url, WebSearchSettings settings) throws IOException {
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid URL: " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IOException("Only http(s) URLs can be fetched: " + url);
        }
        if (uri.getHost() == null) {
            throw new IOException("URL without host: " + url);
        }
        if (!settings.allowPrivateHosts) {
            try {
                for (InetAddress a : InetAddress.getAllByName(uri.getHost())) {
                    if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
                            || a.isAnyLocalAddress() || a.isMulticastAddress()) {
                        throw new IOException("Refusing to fetch a private network address: " + uri.getHost());
                    }
                }
            } catch (java.net.UnknownHostException e) {
                throw new IOException("Unknown host: " + uri.getHost());
            }
        }
        return uri;
    }

    static String productToken(String userAgent) {
        String first = userAgent.strip().split("[\\s/]", 2)[0];
        return first.isEmpty() ? "HarnessBot" : first;
    }

    private static String pathAndQuery(URI uri) {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    private static byte[] readLimited(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (out.size() < max && (n = in.read(buf, 0, Math.min(buf.length, max - out.size()))) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static Charset charset(String contentType) {
        Matcher m = CHARSET.matcher(contentType);
        if (m.find()) {
            try {
                return Charset.forName(m.group(1));
            } catch (IllegalArgumentException e) {
                // fall through to UTF-8
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** Recognizes the interstitial pages of common bot-protection services; such pages are never worked around. */
    static boolean looksLikeChallenge(String html) {
        if (html.length() > 20_000) {
            return false;
        }
        String s = html.toLowerCase(Locale.ROOT);
        return s.contains("checking your browser") || s.contains("cf-challenge") || s.contains("g-recaptcha")
                || s.contains("verify you are human") || s.contains("enable javascript and cookies to continue");
    }
}
