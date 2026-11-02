package net.talaatharb.harness.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolCallParserTest {

    @Test
    void parsesFencedCall() {
        ToolCall call = ToolCallParser.parse("Sure.\n```tool_call\n{\"name\":\"read_file\",\"arguments\":{\"path\":\"a.txt\"}}\n```")
                .orElseThrow();
        assertEquals("read_file", call.name());
        assertEquals("a.txt", call.arguments().get("path"));
    }

    @Test
    void parsesBareJsonCall() {
        assertEquals("current_time", ToolCallParser.parse(" {\"name\":\"current_time\"} ").orElseThrow().name());
    }

    @Test
    void ignoresPlainTextAndInvalidJson() {
        assertTrue(ToolCallParser.parse("just an answer").isEmpty());
        assertTrue(ToolCallParser.parse("```tool_call\n{not json}\n```").isEmpty());
        assertTrue(ToolCallParser.parse("{\"foo\":1}").isEmpty());
    }

    @Test
    void diagnosesBrokenFencedCallsOnly() {
        assertTrue(ToolCallParser.diagnose("```tool_call\n{\"name\": \"x\", \"arguments\": {\n```").orElseThrow()
                .startsWith("invalid JSON"));
        assertTrue(ToolCallParser.diagnose("```tool_call\n[1, 2]\n```").isPresent());
        assertTrue(ToolCallParser.diagnose("```tool_call\n{\"name\":\"x\"}\n```").isEmpty());
        assertTrue(ToolCallParser.diagnose("plain answer with {braces}").isEmpty());
    }

    @Test
    void attachesSeparateJavaBlockAsSourceCode() {
        String reply = "```tool_call\n{\"name\":\"create_tool\",\"arguments\":{\"name\":\"echo\"}}\n```\n"
                + "```java\npublic class Echo {}\n```";
        assertEquals("public class Echo {}", ToolCallParser.parse(reply).orElseThrow().arguments().get("sourceCode"));
    }

    @Test
    void toleratesRawNewlinesAndTrailingCommas() {
        ToolCall call = ToolCallParser.parse("```tool_call\n{\"name\":\"t\",\"arguments\":{\"s\":\"a\nb\",},}\n```")
                .orElseThrow();
        assertEquals("a\nb", call.arguments().get("s"));
    }

    @Test
    void signatureIgnoresArgumentOrder() {
        ToolCall a = ToolCallParser.parse("{\"name\":\"t\",\"arguments\":{\"a\":1,\"b\":2}}").orElseThrow();
        ToolCall b = ToolCallParser.parse("{\"name\":\"t\",\"arguments\":{\"b\":2,\"a\":1}}").orElseThrow();
        assertEquals(a.signature(), b.signature());
    }
}
