package net.talaatharb.harness.reply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;

import org.junit.jupiter.api.Test;

class ReplyFormatTest {

    @Test
    void splitsTextAndUiBlocks() {
        String reply = "Here you go:\n```ui\n{\"type\":\"card\",\"text\":\"T\",\"children\":[{\"type\":\"button\","
                + "\"text\":\"Go\",\"action\":\"do it\"}]}\n```\nAnything else?";
        List<ReplySegment> segments = ReplyFormat.parse(reply, ReplySettings.Mode.AGENTIC_UI);

        assertEquals(3, segments.size());
        assertEquals("Here you go:", ((ReplySegment.Text) segments.get(0)).text());
        UiComponent card = ((ReplySegment.Ui) segments.get(1)).component();
        assertEquals("card", card.type());
        assertEquals("do it", card.children().get(0).action());
        assertEquals("Anything else?", ((ReplySegment.Text) segments.get(2)).text());
    }

    @Test
    void reportsInvalidBlocks() {
        List<ReplySegment> segments = ReplyFormat.parse("```ui\n{broken\n```", ReplySettings.Mode.AGENTIC_UI);
        assertInstanceOf(ReplySegment.Invalid.class, segments.get(0));
    }

    @Test
    void textModeKeepsBlocksAsText() {
        List<ReplySegment> segments = ReplyFormat.parse("```ui\n{}\n```", ReplySettings.Mode.TEXT);
        assertEquals(1, segments.size());
        assertInstanceOf(ReplySegment.Text.class, segments.get(0));
    }
}
