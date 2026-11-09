package net.talaatharb.harness.web;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.config.SettingsStore;

/**
 * Small same-host web crawler: breadth-first from a start URL, following links only to the same host (ignoring a
 * {@code www.} prefix and the http/https difference) up to a link depth and page budget. Fetching goes through
 * {@link PageFetcher}, so robots.txt, per-host pacing and the private-network block of the web settings apply.
 */
public class SiteCrawler {
    public static final int MAX_DEPTH = 5;
    public static final int DEFAULT_DEPTH = 1;
    public static final int MAX_PAGES = 200;
    public static final int DEFAULT_PAGES = 25;
    private static final Logger LOG = LoggerFactory.getLogger(SiteCrawler.class);
    private static final int MAX_QUEUED = 5_000;
    private static final Pattern NON_PAGE = Pattern.compile(
            "(?i).*\\.(pdf|zip|gz|tgz|rar|7z|tar|exe|msi|dmg|iso|apk|jar|png|jpe?g|gif|webp|svg|ico|bmp|tiff?|mp3|wav|"
                    + "ogg|flac|mp4|webm|mov|avi|mkv|css|js|json|woff2?|ttf|eot|docx?|xlsx?|pptx?|csv|rss|atom)$");

    /** A page with readable content, found {@code depth} links away from the start URL. */
    public record Page(ExtractedDocument document, int depth) {
    }

    /** Receives each usable page; may throw to record the page as failed. */
    @FunctionalInterface
    public interface PageSink {
        void accept(Page page) throws Exception;
    }

    @FunctionalInterface
    public interface Progress {
        void update(String message, double fraction);
    }

    public record Result(int accepted, List<WebResearchService.Skipped> skipped,
            List<WebResearchService.Skipped> failures) {
    }

    private record Item(String url, int depth) {
    }

    private final Supplier<HarnessSettings> settings;
    private final PageFetcher fetcher;
    private final ContentExtractor extractor;

    @Inject
    public SiteCrawler(SettingsStore store, PageFetcher fetcher, ContentExtractor extractor) {
        this(store::current, fetcher, extractor);
    }

    public SiteCrawler(Supplier<HarnessSettings> settings, PageFetcher fetcher, ContentExtractor extractor) {
        this.settings = settings;
        this.fetcher = fetcher;
        this.extractor = extractor;
    }

    /**
     * Crawls {@code startUrl}.
     *
     * @param maxDepth  link depth; 0 = only the start page (clamped to 0..{@link #MAX_DEPTH})
     * @param maxPages  pages handed to {@code sink} at most (clamped to 1..{@link #MAX_PAGES})
     * @param skipUrls  normalized URLs ({@link DocumentDeduplicator#normalizeUrl}) not to hand to the sink, e.g.
     *                  pages already indexed; their links are still followed
     * @throws IOException when the start URL is invalid; per-page problems are reported in the result
     */
    public Result crawl(String startUrl, int maxDepth, int maxPages, Set<String> skipUrls, PageSink sink,
            Progress progress) throws IOException {
        HarnessSettings s = settings.get();
        WebSearchSettings web = (s.tools == null || s.tools.web == null ? new WebSearchSettings() : s.tools.web)
                .sanitized();
        URI start = startUri(startUrl);
        String host = siteHost(start);
        int depthLimit = Math.max(0, Math.min(MAX_DEPTH, maxDepth));
        int pageLimit = Math.max(1, Math.min(MAX_PAGES, maxPages));
        Progress p = progress == null ? (m, f) -> { } : progress;
        Set<String> skip = skipUrls == null ? Set.of() : skipUrls;

        Deque<Item> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(new Item(start.toString(), 0));
        seen.add(DocumentDeduplicator.normalizeUrl(start.toString()));
        DocumentDeduplicator contents = new DocumentDeduplicator();
        List<WebResearchService.Skipped> skipped = new ArrayList<>();
        List<WebResearchService.Skipped> failures = new ArrayList<>();
        int accepted = 0;
        int fetched = 0;
        int fetchBudget = pageLimit * 3;
        while (!queue.isEmpty() && accepted < pageLimit && fetched < fetchBudget) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Crawl cancelled");
            }
            Item item = queue.poll();
            fetched++;
            p.update("Fetching (" + accepted + "/" + pageLimit + ", depth " + item.depth() + ") " + item.url(),
                    (double) accepted / pageLimit);
            PageFetcher.FetchedPage page;
            try {
                page = fetcher.fetch(item.url(), web);
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Crawl cancelled");
                }
                LOG.debug("Could not crawl {}: {}", item.url(), e.toString());
                failures.add(new WebResearchService.Skipped(item.url(), message(e)));
                continue;
            }
            URI finalUri = parse(page.finalUrl());
            if (finalUri == null || !host.equals(siteHost(finalUri))) {
                skipped.add(new WebResearchService.Skipped(item.url(), "redirected outside the site to "
                        + page.finalUrl()));
                continue;
            }
            String finalKey = DocumentDeduplicator.normalizeUrl(page.finalUrl());
            boolean redirectedToSeen = !finalKey.equals(DocumentDeduplicator.normalizeUrl(item.url()))
                    && !seen.add(finalKey);
            if (item.depth() < depthLimit && !redirectedToSeen) {
                for (String link : links(page.finalUrl(), page.html(), host)) {
                    if (queue.size() < MAX_QUEUED && seen.add(DocumentDeduplicator.normalizeUrl(link))) {
                        queue.add(new Item(link, item.depth() + 1));
                    }
                }
            }
            if (redirectedToSeen) {
                skipped.add(new WebResearchService.Skipped(item.url(), "same page as " + page.finalUrl()));
                continue;
            }
            if (skip.contains(finalKey) || skip.contains(DocumentDeduplicator.normalizeUrl(item.url()))) {
                skipped.add(new WebResearchService.Skipped(item.url(), "already indexed"));
                continue;
            }
            ExtractedDocument doc = extractor.extract(page.finalUrl(), page.html(), web.maxIndexCharsPerPage);
            if (doc.content().isBlank()) {
                skipped.add(new WebResearchService.Skipped(item.url(), "no readable content"));
                continue;
            }
            String duplicate = contents.addContent(doc.url(), doc.content());
            if (duplicate != null) {
                skipped.add(new WebResearchService.Skipped(item.url(), "duplicate of " + duplicate));
                continue;
            }
            try {
                sink.accept(new Page(doc, item.depth()));
                accepted++;
            } catch (InterruptedIOException | InterruptedException e) {
                throw new InterruptedIOException("Crawl cancelled");
            } catch (Exception e) {
                LOG.error("Could not use crawled page {}", doc.url(), e);
                failures.add(new WebResearchService.Skipped(doc.url(), message(e)));
            }
        }
        p.update("Crawled " + fetched + " page(s)", 1);
        return new Result(accepted, skipped, failures);
    }

    /** RAG text for a crawled page: a title/source/date header followed by the readable content. */
    public static String indexText(ExtractedDocument d) {
        return WebSearchIndexTool.withHeader(d);
    }

    /** Same-site links of a page: absolute http(s), without fragments, skipping obvious non-HTML resources. */
    static List<String> links(String pageUrl, String html, String host) {
        List<String> out = new ArrayList<>();
        for (Element a : Jsoup.parse(html == null ? "" : html, pageUrl).select("a[href]")) {
            String rel = a.attr("rel").toLowerCase(Locale.ROOT);
            if (rel.contains("nofollow")) {
                continue;
            }
            String abs = a.absUrl("href");
            URI uri = parse(abs);
            if (uri == null || uri.getHost() == null || !host.equals(siteHost(uri))) {
                continue;
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (!scheme.equals("http") && !scheme.equals("https") || NON_PAGE.matcher(path).matches()) {
                continue;
            }
            int hash = abs.indexOf('#');
            out.add(hash < 0 ? abs : abs.substring(0, hash));
        }
        return out;
    }

    /** Host plus non-default port, lower-cased, without a leading {@code www.}. */
    static String siteHost(URI uri) {
        String h = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (h.startsWith("www.")) {
            h = h.substring(4);
        }
        int port = uri.getPort();
        return port < 0 || port == 80 || port == 443 ? h : h + ":" + port;
    }

    private static URI startUri(String url) throws IOException {
        String u = url == null ? "" : url.strip();
        if (u.isEmpty()) {
            throw new IOException("Enter a URL to crawl");
        }
        if (!u.contains("://")) {
            u = "https://" + u;
        }
        URI uri = parse(u);
        String scheme = uri == null || uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri == null || uri.getHost() == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new IOException("Not a valid http(s) URL: " + url);
        }
        if (uri.getRawUserInfo() != null) {
            throw new IOException("URLs with user credentials are not crawled");
        }
        return uri.getRawFragment() == null ? uri : parse(u.substring(0, u.indexOf('#')));
    }

    private static URI parse(String url) {
        try {
            return url == null || url.isBlank() ? null : URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
