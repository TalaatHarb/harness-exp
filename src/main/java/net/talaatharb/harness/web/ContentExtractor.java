package net.talaatharb.harness.web;

/** Turns HTML into readable text plus source metadata. */
public interface ContentExtractor {

    /** @param maxChars the content is cut to about this length
     * @return the document; its content is empty when nothing readable was found */
    ExtractedDocument extract(String url, String html, int maxChars);
}
