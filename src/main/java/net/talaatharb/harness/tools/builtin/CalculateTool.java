package net.talaatharb.harness.tools.builtin;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.Map;

import net.talaatharb.harness.tools.Tool;
import net.talaatharb.harness.tools.ToolResult;

/** Bounded arithmetic without evaluating model-supplied code. */
public class CalculateTool implements Tool {
    @Override public String name() { return "calculate"; }

    @Override public String description() {
        return "Deterministic arithmetic: add, subtract, multiply, divide, or is_prime. Use this to verify numerical "
                + "answers instead of guessing. is_prime accepts integers from 0 to 2147483647.";
    }

    @Override public String argumentsHint() {
        return "{\"operation\":\"add|subtract|multiply|divide|is_prime\",\"a\":\"17\",\"b\":\"2\"}";
    }

    @Override public ToolResult execute(Map<String, Object> args) {
        try {
            if (args == null) {
                throw new IllegalArgumentException("Arguments are required");
            }
            String operation = String.valueOf(args.get("operation"));
            BigDecimal a = number(args.get("a"));
            if ("is_prime".equals(operation)) {
                BigInteger n = a.toBigIntegerExact();
                if (n.signum() < 0 || n.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                    throw new IllegalArgumentException("is_prime requires an integer from 0 to 2147483647");
                }
                int value = n.intValueExact();
                boolean prime = value >= 2;
                for (int d = 2; prime && (long) d * d <= value; d++) {
                    if (value % d == 0) {
                        prime = false;
                    }
                }
                return ToolResult.ok(Boolean.toString(prime));
            }
            BigDecimal b = number(args.get("b"));
            BigDecimal result = switch (operation) {
                case "add" -> a.add(b);
                case "subtract" -> a.subtract(b);
                case "multiply" -> a.multiply(b);
                case "divide" -> a.divide(b, MathContext.DECIMAL128);
                default -> throw new IllegalArgumentException("Unknown arithmetic operation: " + operation);
            };
            return ToolResult.ok(result.stripTrailingZeros().toPlainString());
        } catch (IllegalArgumentException | ArithmeticException e) {
            return ToolResult.error("Calculation rejected: " + e.getMessage());
        }
    }

    private static BigDecimal number(Object value) {
        String text = value == null ? "" : value.toString();
        if (text.isBlank() || text.length() > 100) {
            throw new IllegalArgumentException("Provide a number of at most 100 characters");
        }
        BigDecimal number = new BigDecimal(text);
        if (Math.abs((long) number.scale()) > 100 || number.precision() > 100) {
            throw new IllegalArgumentException("Number exceeds precision or scale limit");
        }
        return number;
    }
}
