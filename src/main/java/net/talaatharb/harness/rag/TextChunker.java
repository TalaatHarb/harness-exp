package net.talaatharb.harness.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits text into overlapping chunks of about {@code size} characters, preferring paragraph, then sentence, then
 * word boundaries.
 */
public final class TextChunker {
    public static final int MIN_SIZE = 200;

    private TextChunker() {
    }

    private record Piece(String text, boolean newParagraph) {
    }

    public static List<String> chunk(String text, int size, int overlap) {
        int max = Math.max(MIN_SIZE, size);
        int carry = Math.max(0, Math.min(overlap, max / 2));
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean hasNew = false;
        for (Piece piece : pieces(text == null ? "" : text, max)) {
            if (hasNew && current.length() + 2 + piece.text().length() > max) {
                chunks.add(current.toString().strip());
                String tail = tail(current, carry);
                current.setLength(0);
                current.append(tail);
                hasNew = false;
            }
            if (!current.isEmpty()) {
                current.append(piece.newParagraph() ? "\n\n" : " ");
            }
            current.append(piece.text());
            hasNew = true;
        }
        if (hasNew && !current.toString().isBlank()) {
            chunks.add(current.toString().strip());
        }
        return chunks;
    }

    private static List<Piece> pieces(String text, int max) {
        List<Piece> pieces = new ArrayList<>();
        for (String paragraph : text.replace("\r\n", "\n").replace('\r', '\n').split("\n\\s*\n")) {
            String p = paragraph.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() <= max) {
                pieces.add(new Piece(p, true));
                continue;
            }
            boolean first = true;
            for (String sentence : p.split("(?<=[.!?;:])\\s+")) {
                for (String part : hardSplit(sentence.strip(), max)) {
                    if (!part.isEmpty()) {
                        pieces.add(new Piece(part, first));
                        first = false;
                    }
                }
            }
        }
        return pieces;
    }

    /** Cuts text longer than {@code max} at the last whitespace before the limit (or at the limit). */
    private static List<String> hardSplit(String text, int max) {
        List<String> parts = new ArrayList<>();
        String rest = text;
        while (rest.length() > max) {
            int cut = rest.lastIndexOf(' ', max);
            if (cut < max / 2) {
                cut = max;
            }
            parts.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        parts.add(rest);
        return parts;
    }

    /** The last {@code chars} characters of {@code text}, starting at a word boundary. */
    private static String tail(CharSequence text, int chars) {
        if (chars <= 0 || text.length() == 0) {
            return "";
        }
        String s = text.toString();
        int start = Math.max(0, s.length() - chars);
        if (start > 0) {
            int space = s.indexOf(' ', start);
            int newline = s.indexOf('\n', start);
            int boundary = space < 0 ? newline : newline < 0 ? space : Math.min(space, newline);
            if (boundary < 0) {
                return "";
            }
            start = boundary + 1;
        }
        return s.substring(start).strip();
    }
}
