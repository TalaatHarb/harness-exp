package net.talaatharb.harness.web;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Detects repeated pages: by normalized URL, identical text and near-identical text (mirrors, syndicated copies). */
public class DocumentDeduplicator {
    private static final int SHINGLE = 5;
    private static final double NEAR_DUPLICATE = 0.8;

    private final Map<String, String> urls = new HashMap<>();
    private final Map<String, String> fingerprints = new HashMap<>();
    private final Map<String, Set<Integer>> shingles = new HashMap<>();

    /** @return the URL this one duplicates, or {@code null} when it is new */
    public String duplicateOfUrl(String url) {
        return urls.get(normalizeUrl(url));
    }

    /** Registers the URL; returns the earlier URL when it was already seen, else {@code null}. */
    public String addUrl(String url) {
        String key = normalizeUrl(url);
        String earlier = urls.get(key);
        if (earlier == null) {
            urls.put(key, url);
        }
        return earlier;
    }

    /** Registers the document's text; returns the URL of an earlier near-identical document, else {@code null}. */
    public String addContent(String url, String content) {
        String text = normalizeText(content);
        String exact = Integer.toHexString(text.hashCode()) + ":" + text.length();
        String earlier = fingerprints.get(exact);
        if (earlier != null) {
            return earlier;
        }
        Set<Integer> mine = shingles(text);
        for (Map.Entry<String, Set<Integer>> e : shingles.entrySet()) {
            if (jaccard(mine, e.getValue()) >= NEAR_DUPLICATE) {
                return e.getKey();
            }
        }
        fingerprints.put(exact, url);
        shingles.put(url, mine);
        return null;
    }

    static Set<Integer> shingles(String text) {
        String[] words = text.split(" ");
        Set<Integer> out = new HashSet<>();
        if (words.length <= SHINGLE) {
            out.add(text.hashCode());
            return out;
        }
        for (int i = 0; i + SHINGLE <= words.length; i++) {
            out.add(String.join(" ", java.util.Arrays.copyOfRange(words, i, i + SHINGLE)).hashCode());
        }
        return out;
    }

    static double jaccard(Set<Integer> a, Set<Integer> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int common = 0;
        for (Integer x : a) {
            if (b.contains(x)) {
                common++;
            }
        }
        return (double) common / (a.size() + b.size() - common);
    }

    static String normalizeText(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    /** Lower-cases scheme/host, drops {@code www.}, fragment, default port, tracking parameters and a trailing slash. */
    public static String normalizeUrl(String url) {
        if (url == null) {
            return "";
        }
        try {
            URI u = URI.create(url.strip());
            if (u.getHost() == null) {
                return url.strip();
            }
            String scheme = u.getScheme() == null ? "http" : u.getScheme().toLowerCase(Locale.ROOT);
            String host = u.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            int port = u.getPort();
            boolean defaultPort = port < 0 || (scheme.equals("http") && port == 80)
                    || (scheme.equals("https") && port == 443);
            String path = u.getRawPath() == null ? "" : u.getRawPath();
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            StringBuilder sb = new StringBuilder(scheme.equals("http") ? "https" : scheme).append("://").append(host);
            if (!defaultPort) {
                sb.append(':').append(port);
            }
            sb.append(path);
            String query = cleanQuery(u.getRawQuery());
            if (!query.isEmpty()) {
                sb.append('?').append(query);
            }
            return sb.toString();
        } catch (IllegalArgumentException e) {
            return url.strip();
        }
    }

    private static String cleanQuery(String query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        TreeMap<String, String> kept = new TreeMap<>();
        for (String p : query.split("&")) {
            String key = p.contains("=") ? p.substring(0, p.indexOf('=')) : p;
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.startsWith("utm_") || Set.of("fbclid", "gclid", "msclkid", "ref", "ref_src", "mc_cid", "mc_eid",
                    "igshid", "spm").contains(lower) || key.isEmpty()) {
                continue;
            }
            kept.put(key, p);
        }
        return String.join("&", kept.values());
    }
}
