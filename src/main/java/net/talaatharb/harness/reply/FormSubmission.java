package net.talaatharb.harness.reply;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Pure validation and unambiguous serialization of a form's current values. */
public final class FormSubmission {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FormSubmission() {
    }

    public static String fieldError(String type, String value, boolean required) {
        if (value == null || value.isBlank()) {
            return required ? "Required" : null;
        }
        if (value.length() > UiSchemaValidator.MAX_TEXT_CHARS) {
            return "Value is too long";
        }
        try {
            if ("date".equals(type)) {
                if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    return "Enter a date as YYYY-MM-DD";
                }
                LocalDate.parse(value);
            } else if ("number".equals(type)) {
                new BigDecimal(value);
            }
        } catch (RuntimeException ex) {
            return "date".equals(type) ? "Enter a date as YYYY-MM-DD" : "Enter a number";
        }
        return null;
    }

    public static String message(String action, Map<String, String> values) {
        try {
            return action + "\nForm values: " + MAPPER.writeValueAsString(values)
                    + "\nThese are submitted preferences, not confirmation of a booking or external action.";
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Cannot serialize form values", ex);
        }
    }
}
