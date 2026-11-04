package net.talaatharb.harness.reply;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class MarkdownTextTest {
    @Test
    void keepsSingleLineBreaksOutsideCodeAndTables() {
        assertEquals("An old pond  \nA frog jumps in  \nSplash", MarkdownText.prepare("An old pond\nA frog jumps in\nSplash"));
        assertEquals("a\n\nb", MarkdownText.prepare("a\n\nb"));
        assertEquals("x  \n```\ncode\nmore\n```\ny", MarkdownText.prepare("x\n```\ncode\nmore\n```\ny"));
        assertEquals("| A |\n|---|\n| 1 |", MarkdownText.prepare("| A |\n|---|\n| 1 |"));
        assertEquals("ends with break  \nnext", MarkdownText.prepare("ends with break  \nnext"));
        assertEquals("a  \nb", MarkdownText.prepare("a\r\nb"));
        assertEquals("", MarkdownText.prepare(null));
    }

    @Test
    void convertsMathButNotCurrencyCodeOrEscapes() {
        String inline = MarkdownText.convertMath("Solve $a(x)y'' + b(x)y' = g(x)$ now");
        assertTrue(inline.startsWith("Solve ![math](" + MarkdownText.INLINE_MATH), inline);
        assertTrue(inline.endsWith(") now"), inline);
        String url = inline.substring(inline.indexOf('(') + 1, inline.lastIndexOf(')'));
        assertEquals("a(x)y'' + b(x)y' = g(x)", MarkdownText.mathSource(url));

        String display = MarkdownText.convertMath("Then\n$$\n\\int_0^1 x\\,dx\n$$\nend");
        String displayUrl = display.substring(display.indexOf("](") + 2, display.lastIndexOf(')'));
        assertTrue(displayUrl.startsWith(MarkdownText.DISPLAY_MATH), display);
        assertEquals("\\int_0^1 x\\,dx", MarkdownText.mathSource(displayUrl));
        assertTrue(MarkdownText.convertMath("\\(x^2\\) and \\[y\\]").matches(
                "!\\[math]\\(latex-inline:[^)]+\\) and !\\[math]\\(latex-display:[^)]+\\)"));

        for (String unchanged : List.of("It costs $5 and $10.", "Price: $ 5 $", "`$x$` in code",
                "```\n$x$\n```", "escaped \\$x\\$", "a $ b", "$x\ny$")) {
            assertEquals(unchanged, MarkdownText.convertMath(unchanged), unchanged);
        }
        assertNull(MarkdownText.mathSource("https://example.com/x.png"));
    }

    @Test
    void onlyWebLinksOpenAndOnlyKnownImageSchemesLoad() {
        assertTrue(MarkdownText.isOpenableLink("https://example.com"));
        assertTrue(MarkdownText.isOpenableLink("mailto:a@b.c"));
        assertFalse(MarkdownText.isOpenableLink("file:///C:/Windows/system32/calc.exe"));
        assertFalse(MarkdownText.isOpenableLink("javascript:alert(1)"));
        assertFalse(MarkdownText.isOpenableLink("not a uri"));
        assertTrue(MarkdownText.isLoadableImage("https://example.com/a.png"));
        assertTrue(MarkdownText.isLoadableImage("data:image/png;base64,AAAA"));
        assertFalse(MarkdownText.isLoadableImage("jar:file:/x.jar!/a.png"));
    }
}
