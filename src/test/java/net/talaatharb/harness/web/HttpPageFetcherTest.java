package net.talaatharb.harness.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

class HttpPageFetcherTest {
    private HttpServer server;
    private String base;
    private final WebSearchSettings settings = new WebSearchSettings();

    @BeforeEach
    void start() throws IOException {
        settings.allowPrivateHosts = true;
        settings.minHostDelayMillis = 0;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        respond("/robots.txt", 200, "text/plain", "User-agent: *\nDisallow: /private\n");
        respond("/ok", 200, "text/html; charset=utf-8", "<html><body><p>hello</p></body></html>");
        respond("/private/x", 200, "text/html", "<html>secret</html>");
        respond("/limited", 429, "text/html", "slow down");
        respond("/pdf", 200, "application/pdf", "%PDF");
        respond("/challenge", 200, "text/html", "<html>Checking your browser before accessing</html>");
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "/ok");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void respond(String path, int status, String type, String body) {
        server.createContext(path, ex -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", type);
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
    }

    @Test
    void fetchesHtmlAndFollowsRedirects() throws IOException {
        PageFetcher.FetchedPage p = new HttpPageFetcher().fetch(base + "/redirect", settings);
        assertEquals(base + "/ok", p.finalUrl());
        assertTrue(p.html().contains("hello"));
    }

    @Test
    void honoursRobotsTxtAndCanBeSwitchedOff() throws IOException {
        HttpPageFetcher fetcher = new HttpPageFetcher();
        IOException e = assertThrows(IOException.class, () -> fetcher.fetch(base + "/private/x", settings));
        assertTrue(e.getMessage().contains("robots.txt"));
        settings.respectRobotsTxt = false;
        assertTrue(fetcher.fetch(base + "/private/x", settings).html().contains("secret"));
    }

    @Test
    void doesNotWorkAroundRefusalsChallengesOrNonHtml() {
        HttpPageFetcher fetcher = new HttpPageFetcher();
        assertTrue(assertThrows(IOException.class, () -> fetcher.fetch(base + "/limited", settings))
                .getMessage().contains("429"));
        assertTrue(assertThrows(IOException.class, () -> fetcher.fetch(base + "/challenge", settings))
                .getMessage().contains("Anti-bot"));
        assertThrows(IOException.class, () -> fetcher.fetch(base + "/pdf", settings));
    }

    @Test
    void refusesPrivateHostsAndNonHttpSchemesByDefault() {
        settings.allowPrivateHosts = false;
        HttpPageFetcher fetcher = new HttpPageFetcher();
        assertThrows(IOException.class, () -> fetcher.fetch(base + "/ok", settings));
        assertThrows(IOException.class, () -> fetcher.fetch("file:///etc/passwd", settings));
        assertThrows(IOException.class, () -> fetcher.fetch("ftp://example.com/x", settings));
    }
}
