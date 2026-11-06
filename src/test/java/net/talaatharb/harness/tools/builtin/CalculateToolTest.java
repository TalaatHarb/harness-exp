package net.talaatharb.harness.tools.builtin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import net.talaatharb.harness.tools.ToolResult;

class CalculateToolTest {
    private final CalculateTool tool = new CalculateTool();

    @Test void evaluatesExpressionsWithPrecedenceDecimalsAndUnarySigns() {
        assertEquals("22.5", expression("(2 + 3) * 4.5").output());
        assertEquals("14", expression("2+3*4").output());
        assertEquals("3", expression("8 / 2 - 1").output());
        assertEquals("2", expression("8 % 3").output());
        assertEquals("-1.5", expression("-(.5 + 1.)").output());
        assertEquals("6", expression("2*-(-3)").output());
        assertEquals("0.3", expression("0.1+0.2").output());
        assertEquals("0.3333333333333333333333333333333333", expression("1/3").output());
    }

    @Test void removesUnsupportedCharactersRatherThanExecutingThem() {
        assertEquals("14", expression("Answer: (2 + 5) * 2 = ?").output());
        assertEquals("15", expression("1a2 + 3").output());
        assertEquals("1234.5", expression("$1,234.50").output());
    }

    @Test void rejectsInvalidAndUnboundedExpressions() {
        for (String input : new String[] { "", "hello", ".", "1..2", "2+", "(2+3", "2+3)", "()",
                "2(3)", "1/0", "1%0", "(".repeat(65) + "1" + ")".repeat(65), "1".repeat(101),
                "1".repeat(4_097), ("9".repeat(100) + "*").repeat(11) + "9".repeat(100) }) {
            ToolResult result = expression(input);
            assertFalse(result.success(), input);
            assertTrue(result.output().startsWith("Calculation rejected:"), result.output());
        }
        assertFalse(tool.execute(Map.of("expression", 2)).success());
    }

    private ToolResult expression(String text) {
        return tool.execute(Map.of("expression", text));
    }

    @Test void arithmeticAndPrimalityAreDeterministic() {
        assertEquals("true", tool.execute(Map.of("operation", "is_prime", "a", 17)).output());
        assertEquals("false", tool.execute(Map.of("operation", "is_prime", "a", 1)).output());
        assertEquals("false", tool.execute(Map.of("operation", "is_prime", "a", 21)).output());
        assertEquals("8.04672", tool.execute(Map.of("operation", "multiply", "a", 5, "b", "1.609344")).output());
    }

    @Test void rejectsUnboundedAndInvalidCalculations() {
        assertFalse(tool.execute(Map.of("operation", "add", "a", "1e999999999", "b", 1)).success());
        assertFalse(tool.execute(Map.of("operation", "divide", "a", 1, "b", 0)).success());
        assertFalse(tool.execute(Map.of("operation", "is_prime", "a", "2.5")).success());
        assertFalse(tool.execute(Map.of("operation", "is_prime", "a", "2147483648")).success());
        assertFalse(tool.execute(Map.of("operation", "eval", "a", 1, "b", 1)).success());
    }
}
