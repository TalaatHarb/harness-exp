package net.talaatharb.harness.rag;

import java.io.IOException;
import java.util.List;

/** Turns text into vectors. Vectors from different embedders (different {@link #id()}s) are not comparable. */
public interface Embedder {

    /** Identifies the model and version, e.g. {@code ollama:nomic-embed-text}; stored with every document. */
    String id();

    /** @return one vector per text, in order */
    List<float[]> embed(List<String> texts) throws IOException;

    static double cosine(float[] a, float[] b) {
        if (a.length != b.length || a.length == 0) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
