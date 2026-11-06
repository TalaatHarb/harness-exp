package net.talaatharb.harness.web;

import java.time.Instant;

/** Readable content of one web page together with its source metadata. */
public record ExtractedDocument(String url, String title, String content, String publishedDate, String siteName,
        String snippet, Instant fetchedAt) {

    public ExtractedDocument withContent(String newContent) {
        return new ExtractedDocument(url, title, newContent, publishedDate, siteName, snippet, fetchedAt);
    }

    public ExtractedDocument withDetails(String newTitle, String newPublishedDate, String newSnippet) {
        return new ExtractedDocument(url, newTitle, content, newPublishedDate, siteName, newSnippet, fetchedAt);
    }
}
