package net.talaatharb.harness.web;

import java.io.IOException;

/** Retrieves the raw HTML of a page. Implementations must honour access restrictions (robots.txt, status codes). */
public interface PageFetcher {

    /** A downloaded page; {@code finalUrl} differs from the request URL after redirects. */
    record FetchedPage(String finalUrl, String html, String contentType) {
    }

    /** @throws IOException when the page is unreachable, disallowed, not HTML or too big to be useful */
    FetchedPage fetch(String url, WebSearchSettings settings) throws IOException;
}
