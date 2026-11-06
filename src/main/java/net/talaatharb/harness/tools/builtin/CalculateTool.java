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
        return "Evaluate an expression string using +, -, *, /, %, decimals and parentheses. Other characters "
                + "are removed (so '1a2' becomes '12'); no functions, powers or scientific notation. "
                + "Also supports add, subtract, multiply, divide, or is_prime. Use this to verify numerical "
                + "answers instead of guessing. is_prime accepts integers from 0 to 2147483647.";
    }

    @Override public String argumentsHint() {
        return "{\"expression\":\"(2 + 3) * 4.5\"} or "
                + "{\"operation\":\"add|subtract|multiply|divide|is_prime\",\"a\":\"17\",\"b\":\"2\"}";
    }

    @Override public ToolResult execute(Map<String, Object> args) {
        try {
            if (args == null) {
                throw new IllegalArgumentException("Arguments are required");
            }
            if (args.containsKey("expression")) {
                if (!(args.get("expression") instanceof String expression)) {
                    throw new IllegalArgumentException("expression must be a string");
                }
                return ToolResult.ok(new Expression(expression).evaluate().stripTrailingZeros().toPlainString());
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

    private static final class Expression {
        private final String text;
        private int position;
        private int depth;

        Expression(String input) {
            if (input.length() > 4_096) {
                throw new IllegalArgumentException("Expression exceeds 4096 characters");
            }
            text = input.replaceAll("[^0-9.+*/%()\\-]", "");
        }

        BigDecimal evaluate() {
            if (text.isEmpty()) {
                throw new IllegalArgumentException("Expression contains no arithmetic");
            }
            BigDecimal result = sum();
            if (position != text.length()) {
                throw invalid();
            }
            return result;
        }

        private BigDecimal sum() {
            BigDecimal result = product();
            while (position < text.length()) {
                if (take('+')) {
                    result = bounded(result.add(product()));
                } else if (take('-')) {
                    result = bounded(result.subtract(product()));
                } else {
                    break;
                }
            }
            return result;
        }

        private BigDecimal product() {
            BigDecimal result = operand();
            while (position < text.length()) {
                if (take('*')) {
                    result = bounded(result.multiply(operand()));
                } else if (take('/')) {
                    result = bounded(result.divide(operand(), MathContext.DECIMAL128));
                } else if (take('%')) {
                    result = bounded(result.remainder(operand()));
                } else {
                    break;
                }
            }
            return result;
        }

        private BigDecimal operand() {
            boolean negative = false;
            while (position < text.length() && (text.charAt(position) == '+' || text.charAt(position) == '-')) {
                negative ^= text.charAt(position++) == '-';
            }
            BigDecimal result;
            if (take('(')) {
                if (++depth > 64) {
                    throw new IllegalArgumentException("Expression nesting exceeds 64 parentheses");
                }
                result = sum();
                if (!take(')')) {
                    throw invalid();
                }
                depth--;
            } else {
                int start = position;
                while (position < text.length()
                        && (text.charAt(position) == '.' || (text.charAt(position) >= '0'
                                && text.charAt(position) <= '9'))) {
                    position++;
                }
                if (position == start) {
                    throw invalid();
                }
                result = number(text.substring(start, position));
            }
            return negative ? result.negate() : result;
        }

        private boolean take(char character) {
            if (position < text.length() && text.charAt(position) == character) {
                position++;
                return true;
            }
            return false;
        }

        private BigDecimal bounded(BigDecimal value) {
            if (value.precision() > 1_000 || Math.abs((long) value.scale()) > 1_000) {
                throw new IllegalArgumentException("Expression result exceeds precision or scale limit");
            }
            return value;
        }

        private IllegalArgumentException invalid() {
            return new IllegalArgumentException("Invalid arithmetic at filtered expression position " + position);
        }
    }
}
