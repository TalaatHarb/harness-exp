package net.talaatharb.harness.tools.builtin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CalculateToolTest {
    private final CalculateTool tool = new CalculateTool();

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
