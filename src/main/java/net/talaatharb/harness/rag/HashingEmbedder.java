package net.talaatharb.harness.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Offline embedder: hashed, log-weighted bag of words and word pairs, L2-normalised. Needs no model and is fast, but
 * only matches shared words (no synonyms or meaning), so an embedding model gives much better retrieval.
 */
public class HashingEmbedder implements Embedder {
    public static final String ID = "builtin:hash-1024-v1";
    static final int DIMENSIONS = 1024;
    private static final Set<String> STOP_WORDS = Set.of("a", "an", "the", "and", "or", "but", "of", "to", "in",
            "on", "at", "for", "with", "by", "from", "is", "are", "was", "were", "be", "been", "it", "its", "this",
            "that", "these", "those", "as", "if", "then", "so", "do", "does", "did", "i", "you", "we", "they", "he",
            "she", "my", "your", "our", "their", "me", "us", "them", "what", "which", "who", "how", "when", "where",
            "why", "can", "could", "would", "should", "will", "not", "no", "about", "into", "than", "there", "here");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(vector(text));
        }
        return vectors;
    }

    static float[] vector(String text) {
        List<String> words = words(text);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < words.size(); i++) {
            counts.merge(words.get(i), 1, Integer::sum);
            if (i + 1 < words.size()) {
                counts.merge(words.get(i) + ' ' + words.get(i + 1), 1, Integer::sum);
            }
        }
        float[] v = new float[DIMENSIONS];
        counts.forEach((feature, count) -> {
            int h = mix(feature.hashCode());
            float weight = (float) (1 + Math.log(count)) * (feature.indexOf(' ') >= 0 ? 0.5f : 1f);
            v[Math.floorMod(h, DIMENSIONS)] += (h & 0x40000000) == 0 ? weight : -weight;
        });
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        if (norm > 0) {
            float inv = (float) (1 / Math.sqrt(norm));
            for (int i = 0; i < v.length; i++) {
                v[i] *= inv;
            }
        }
        return v;
    }

    static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        if (text == null) {
            return words;
        }
        for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.isEmpty() || STOP_WORDS.contains(w)) {
                continue;
            }
            words.add(stem(w));
        }
        return words;
    }

    private static String stem(String w) {
        if (w.length() > 4 && w.endsWith("ies")) {
            return w.substring(0, w.length() - 3) + "y";
        }
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }

    private static int mix(int h) {
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        return h ^ (h >>> 16);
    }
}
