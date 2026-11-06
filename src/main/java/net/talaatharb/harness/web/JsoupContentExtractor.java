package net.talaatharb.harness.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Readability-style extraction with JSoup: strips page chrome (navigation, ads, forms, scripts), picks the main
 * content container ({@code article}/{@code main}, else the block with the most paragraph text), then keeps headings,
 * paragraphs, list items, quotes and code, dropping very short boilerplate lines and repeated lines.
 */
public class JsoupContentExtractor implements ContentExtractor {
    private static final String NOISE = "script, style, noscript, template, svg, canvas, iframe, form, button, "
            + "input, select, textarea, nav, footer, aside, header, dialog, [role=navigation], [role=banner], "
            + "[role=contentinfo], [role=complementary], [aria-hidden=true], [hidden]";
    private static final String NOISE_CLASSES = "[class~=(?i)(^|[-_ ])(nav|navbar|menu|sidebar|footer|cookie|banner|"
            + "advert|ads?|promo|popup|modal|share|social|comments?|related|breadcrumbs?|newsletter|subscribe)([-_ ]|$)], "
            + "[id~=(?i)(^|[-_ ])(nav|navbar|menu|sidebar|footer|cookie|banner|advert|ads?|promo|popup|modal|share|"
            + "social|comments?|related|breadcrumbs?|newsletter|subscribe)([-_ ]|$)]";
    private static final String TEXT_BLOCKS = "h1, h2, h3, h4, h5, h6, p, li, blockquote, pre, td, th, figcaption, dd, dt";
    private static final String[] DATE_META = { "meta[property=article:published_time]", "meta[name=article:published_time]",
            "meta[property=og:published_time]", "meta[name=date]", "meta[name=pubdate]", "meta[name=publishdate]",
            "meta[name=DC.date.issued]", "meta[name=dcterms.date]", "meta[itemprop=datePublished]",
            "meta[property=og:updated_time]" };
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MIN_LINE_CHARS = 25;

    @Override
    public ExtractedDocument extract(String url, String html, int maxChars) {
        Document doc = Jsoup.parse(html == null ? "" : html, url == null ? "" : url);
        String title = title(doc);
        String published = publishedDate(doc);
        String site = meta(doc, "meta[property=og:site_name]");
        String description = firstNonBlank(meta(doc, "meta[property=og:description]"),
                meta(doc, "meta[name=description]"));

        doc.select(NOISE).remove();
        try {
            // Page-wide feature flags can contain "menu"/"sidebar" without being boilerplate containers.
            doc.select(NOISE_CLASSES).not("html, body").remove();
        } catch (RuntimeException e) {
            // unsupported selector in a particular jsoup version: fall back to tag-based cleaning only
        }
        Element root = mainContainer(doc);
        String content = readableText(root);
        return new ExtractedDocument(url, title, truncate(content, maxChars), published, site, description,
                Instant.now());
    }

    private static Element mainContainer(Document doc) {
        for (String selector : new String[] { "article", "main", "[role=main]", "[itemprop=articleBody]" }) {
            Elements found = doc.select(selector);
            Element best = null;
            int bestLen = 0;
            for (Element e : found) {
                int len = paragraphChars(e);
                if (len > bestLen) {
                    best = e;
                    bestLen = len;
                }
            }
            if (best != null && bestLen >= 200) {
                return best;
            }
        }
        // Fall back to the container holding the most paragraph text.
        Element body = doc.body() == null ? doc : doc.body();
        Element best = body;
        int bestLen = 0;
        for (Element e : body.select("div, section, td")) {
            int own = 0;
            for (Element child : e.children()) {
                if (child.tagName().equals("p") || child.tagName().matches("h[1-6]|pre|blockquote|ul|ol")) {
                    own += child.text().length();
                }
            }
            if (own > bestLen) {
                bestLen = own;
                best = e;
            }
        }
        return bestLen >= 200 ? best : body;
    }

    private static int paragraphChars(Element e) {
        int sum = 0;
        for (Element p : e.select("p, pre, li")) {
            sum += p.text().length();
        }
        return sum;
    }

    private static String readableText(Element root) {
        List<String> lines = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element e : root.select(TEXT_BLOCKS)) {
            // skip containers whose nested blocks are emitted on their own (e.g. li > p)
            if (e.select(TEXT_BLOCKS).size() > 1) {
                continue;
            }
            String text = e.tagName().equals("pre") ? e.wholeText().strip() : normalize(e.text());
            if (text.isEmpty()) {
                continue;
            }
            boolean heading = e.tagName().matches("h[1-6]");
            if (!heading && !e.tagName().equals("pre") && text.length() < MIN_LINE_CHARS) {
                continue;
            }
            if (!seen.add(text.toLowerCase(Locale.ROOT))) {
                continue;
            }
            lines.add(heading ? "## " + text : text);
        }
        // A heading with no text after it carries no information.
        while (!lines.isEmpty() && lines.get(lines.size() - 1).startsWith("## ")) {
            lines.remove(lines.size() - 1);
        }
        return String.join("\n\n", lines);
    }

    private static String title(Document doc) {
        String t = firstNonBlank(meta(doc, "meta[property=og:title]"), doc.title());
        if (t.isBlank()) {
            Element h1 = doc.selectFirst("h1");
            t = h1 == null ? "" : h1.text();
        }
        return normalize(t);
    }

    private static String publishedDate(Document doc) {
        for (String selector : DATE_META) {
            String v = meta(doc, selector);
            if (!v.isBlank()) {
                return v;
            }
        }
        Element time = doc.selectFirst("time[datetime][itemprop=datePublished], time[datetime][pubdate], time[datetime]");
        if (time != null && !time.attr("datetime").isBlank()) {
            return time.attr("datetime").strip();
        }
        for (Element ld : doc.select("script[type=application/ld+json]")) {
            try {
                String found = findDate(JSON.readTree(ld.data()));
                if (!found.isBlank()) {
                    return found;
                }
            } catch (Exception e) {
                // malformed JSON-LD is common; ignore it
            }
        }
        return "";
    }

    private static String findDate(JsonNode node) {
        if (node == null) {
            return "";
        }
        if (node.isObject()) {
            JsonNode d = node.get("datePublished");
            if (d != null && d.isTextual()) {
                return d.asText().strip();
            }
            for (JsonNode child : node) {
                String r = findDate(child);
                if (!r.isBlank()) {
                    return r;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String r = findDate(child);
                if (!r.isBlank()) {
                    return r;
                }
            }
        }
        return "";
    }

    private static String meta(Document doc, String selector) {
        Element e = doc.selectFirst(selector);
        return e == null ? "" : e.attr("content").strip();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b == null ? "" : b;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("[\\s\\u00a0]+", " ").strip();
    }

    /** Cuts at a paragraph or sentence boundary near {@code limit} when possible. */
    static String truncate(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        int cut = text.lastIndexOf("\n\n", limit);
        if (cut < limit / 2) {
            cut = text.lastIndexOf(". ", limit);
            cut = cut < limit / 2 ? limit : cut + 1;
        }
        return text.substring(0, cut).strip() + " …";
    }
}
