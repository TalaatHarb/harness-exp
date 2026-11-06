package net.talaatharb.harness.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

import net.talaatharb.harness.config.HarnessSettings;
import net.talaatharb.harness.config.SettingsStore;

/** The search → fetch → extract → de-duplicate pipeline shared by {@code web_search_extract} and {@code web_search_index}. */
public class WebResearchService {
    private static final Logger LOG = LoggerFactory.getLogger(WebResearchService.class);

    /** A page that was not used, with the reason (duplicate, already indexed, blocked, empty, ...). */
    public record Skipped(String url, String reason) {
    }

    /** @param results every normalized search hit; @param documents extracted pages, best-ranked first */
    public record Research(String provider, String query, List<SearchResult> results, List<ExtractedDocument> documents,
            List<Skipped> skipped, List<Skipped> failures) {
    }

    private final Supplier<HarnessSettings> settings;
    private final SearchProviderRegistry providers;
    private final PageFetcher fetcher;
    private final ContentExtractor extractor;

    @Inject
    public WebResearchService(SettingsStore store, SearchProviderRegistry providers, PageFetcher fetcher,
            ContentExtractor extractor) {
        this(store::current, providers, fetcher, extractor);
    }

    public WebResearchService(Supplier<HarnessSettings> settings, SearchProviderRegistry providers,
            PageFetcher fetcher, ContentExtractor extractor) {
        this.settings = settings;
        this.providers = providers;
        this.fetcher = fetcher;
        this.extractor = extractor;
    }

    public WebSearchSettings settings() {
        HarnessSettings s = settings.get();
        if (s.tools.web == null) {
            s.tools.web = new WebSearchSettings();
        }
        return s.tools.web.sanitized();
    }

    /** Searches with the configured provider; hits are normalized and de-duplicated by URL. */
    public List<SearchResult> search(String query) throws IOException {
        WebSearchSettings web = settings();
        SearchProvider provider = providers.find(web.provider).orElseThrow(
                () -> new IOException("Unknown search provider '" + web.provider + "'; pick one in Tools settings."));
        List<SearchResult> raw = provider.search(query, web.maxResults, web.configFor(provider.id()), web);
        DocumentDeduplicator urls = new DocumentDeduplicator();
        List<SearchResult> out = new ArrayList<>();
        for (SearchResult r : raw) {
            if (!r.url().isBlank() && urls.addUrl(r.url()) == null) {
                out.add(r);
            }
        }
        return out.size() > web.maxResults ? out.subList(0, web.maxResults) : out;
    }

    /**
     * Searches and fetches pages until {@code maxPages} usable documents were collected (or results ran out).
     *
     * @param maxPages cap for this call (the configured maximum applies as well)
     * @param charLimit content limit per document
     * @param alreadyKnown returns a reason when a URL must not be fetched (e.g. already indexed), else {@code null}
     */
    public Research research(String query, int maxPages, int charLimit, Function<String, String> alreadyKnown)
            throws IOException {
        WebSearchSettings web = settings();
        List<SearchResult> results = search(query);
        int wanted = Math.max(1, Math.min(maxPages, web.maxPages));
        DocumentDeduplicator dedup = new DocumentDeduplicator();
        List<ExtractedDocument> docs = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        List<Skipped> failures = new ArrayList<>();
        for (SearchResult r : results) {
            if (docs.size() >= wanted) {
                break;
            }
            String known = alreadyKnown == null ? null : alreadyKnown.apply(r.url());
            if (known != null) {
                skipped.add(new Skipped(r.url(), known));
                continue;
            }
            try {
                PageFetcher.FetchedPage page = fetcher.fetch(r.url(), web);
                String finalKnown = alreadyKnown == null ? null : alreadyKnown.apply(page.finalUrl());
                if (finalKnown != null) {
                    skipped.add(new Skipped(r.url(), finalKnown));
                    continue;
                }
                ExtractedDocument doc = extractor.extract(page.finalUrl(), page.html(), charLimit);
                if (doc.content().length() < web.minContentChars) {
                    skipped.add(new Skipped(r.url(), "no readable content"));
                    continue;
                }
                String title = doc.title().isBlank() ? r.title() : doc.title();
                String date = doc.publishedDate().isBlank() ? r.publishedDate() : doc.publishedDate();
                String snippet = r.snippet().isBlank() ? doc.snippet() : r.snippet();
                doc = doc.withDetails(title, date, snippet);
                String dupUrl = dedup.addUrl(doc.url());
                String dupContent = dupUrl != null ? dupUrl : dedup.addContent(doc.url(), doc.content());
                if (dupContent != null) {
                    skipped.add(new Skipped(r.url(), "duplicate of " + dupContent));
                    continue;
                }
                docs.add(doc);
            } catch (IOException | RuntimeException e) {
                LOG.debug("Could not use {}: {}", r.url(), e.toString());
                failures.add(new Skipped(r.url(), e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
        return new Research(web.provider, query, results, docs, skipped, failures);
    }
}
