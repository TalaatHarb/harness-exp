package net.talaatharb.harness.reply;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/** Pure helpers for showing model text as Markdown. */
public final class MarkdownText {
    public static final String INLINE_MATH = "latex-inline:";
    public static final String DISPLAY_MATH = "latex-display:";

    private MarkdownText() {
    }

    /**
     * Prepares model text for Markdown rendering: math ({@code $..$}, {@code $$..$$}, {@code \(..\)},
     * {@code \[..\]}) becomes internal image tokens rendered locally, and single line breaks are kept as line
     * breaks (Markdown would join them, which breaks poems and plain-text answers). Code is left untouched.
     */
    public static String prepare(String text) {
        return keepLineBreaks(convertMath(text));
    }

    /** Replaces math outside code with {@code ![math](latex-inline:BASE64)} / {@code latex-display:} images. */
    public static String convertMath(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String s = text.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        boolean lineStart = true;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (lineStart) {
                int indent = i;
                while (indent < s.length() && (s.charAt(indent) == ' ' || s.charAt(indent) == '\t')) {
                    indent++;
                }
                if (s.startsWith("```", indent) || s.startsWith("~~~", indent)) {
                    String fence = s.substring(indent, indent + 3);
                    int close = s.indexOf("\n", indent);
                    int end = s.length();
                    while (close >= 0) {
                        int next = close + 1;
                        int ws = next;
                        while (ws < s.length() && (s.charAt(ws) == ' ' || s.charAt(ws) == '\t')) {
                            ws++;
                        }
                        if (s.startsWith(fence, ws)) {
                            int eol = s.indexOf('\n', ws);
                            end = eol < 0 ? s.length() : eol;
                            break;
                        }
                        close = s.indexOf('\n', next);
                    }
                    out.append(s, i, end);
                    i = end;
                    lineStart = false;
                    continue;
                }
            }
            lineStart = c == '\n';
            if (c == '`') {
                int run = i;
                while (run < s.length() && s.charAt(run) == '`') {
                    run++;
                }
                String ticks = s.substring(i, run);
                int close = s.indexOf(ticks, run);
                int end = close < 0 ? run : close + ticks.length();
                out.append(s, i, end);
                i = end;
                continue;
            }
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '(' || n == '[') {
                    String closer = n == '(' ? "\\)" : "\\]";
                    int close = s.indexOf(closer, i + 2);
                    if (close > i + 2) {
                        out.append(token(s.substring(i + 2, close), n == '['));
                        i = close + 2;
                        continue;
                    }
                }
                out.append(c).append(n);
                i += 2;
                continue;
            }
            if (c == '$') {
                if (s.startsWith("$$", i)) {
                    int close = s.indexOf("$$", i + 2);
                    if (close > i + 2 && !s.substring(i + 2, close).isBlank()) {
                        out.append(token(s.substring(i + 2, close), true));
                        i = close + 2;
                        continue;
                    }
                } else {
                    int close = inlineMathEnd(s, i);
                    if (close > 0) {
                        out.append(token(s.substring(i + 1, close), false));
                        i = close + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * Pandoc's rule for {@code $..$}: the opening {@code $} is followed by a non-space, the closing one is preceded
     * by a non-space and not followed by a digit, and both are on the same line ({@code $5 and $10} stays text).
     */
    private static int inlineMathEnd(String s, int open) {
        if (open + 1 >= s.length() || Character.isWhitespace(s.charAt(open + 1))) {
            return -1;
        }
        for (int j = open + 1; j < s.length(); j++) {
            char ch = s.charAt(j);
            if (ch == '\n') {
                return -1;
            }
            if (ch == '\\') {
                j++;
            } else if (ch == '$') {
                boolean closes = !Character.isWhitespace(s.charAt(j - 1))
                        && (j + 1 >= s.length() || !Character.isDigit(s.charAt(j + 1)));
                return closes && j > open + 1 ? j : -1;
            }
        }
        return -1;
    }

    private static String token(String tex, boolean display) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tex.strip().getBytes(StandardCharsets.UTF_8));
        return "![math](" + (display ? DISPLAY_MATH : INLINE_MATH) + encoded + ")";
    }

    /** Decodes a math image URL back to its TeX source, or {@code null} if {@code url} is not a math token. */
    public static String mathSource(String url) {
        if (url == null) {
            return null;
        }
        String data = url.startsWith(INLINE_MATH) ? url.substring(INLINE_MATH.length())
                : url.startsWith(DISPLAY_MATH) ? url.substring(DISPLAY_MATH.length()) : null;
        if (data == null) {
            return null;
        }
        try {
            return new String(Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String keepLineBreaks(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder out = new StringBuilder(text.length() + lines.length * 2);
        String fence = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.stripLeading();
            if (fence == null && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) {
                fence = trimmed.substring(0, 3);
            } else if (fence != null && trimmed.startsWith(fence)) {
                fence = null;
            } else if (fence == null && i + 1 < lines.length && !line.isBlank() && !lines[i + 1].isBlank()
                    && !line.endsWith("  ") && !line.endsWith("\\") && !isTableRow(trimmed)
                    && !isTableRow(lines[i + 1].stripLeading())) {
                line = line + "  ";
            }
            out.append(line);
            if (i + 1 < lines.length) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static boolean isTableRow(String line) {
        return line.startsWith("|");
    }

    /** Only these links can be opened from a reply (after confirmation); anything else can just be copied. */
    public static boolean isOpenableLink(String link) {
        String scheme = scheme(link);
        return scheme.equals("http") || scheme.equals("https") || scheme.equals("mailto");
    }

    /** Images the user allowed can only come from these schemes (no {@code jar:}, custom handlers, …). */
    public static boolean isLoadableImage(String url) {
        String scheme = scheme(url);
        return scheme.equals("http") || scheme.equals("https") || scheme.equals("data") || scheme.equals("file");
    }

    static String scheme(String link) {
        if (link == null) {
            return "";
        }
        try {
            String scheme = URI.create(link.strip()).getScheme();
            return scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
