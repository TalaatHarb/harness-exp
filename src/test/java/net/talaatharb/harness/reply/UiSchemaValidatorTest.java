package net.talaatharb.harness.reply;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class UiSchemaValidatorTest {
    private static String form(String children) {
        return "{\"type\":\"form\",\"action\":\"Find availability\",\"children\":[" + children + "]}";
    }

    private static String input(String name, String type) {
        return "{\"type\":\"input\",\"text\":\"Field\",\"props\":{\"name\":\"" + name
                + "\",\"inputType\":\"" + type + "\",\"required\":true}}";
    }

    @Test
    void acceptsEditableFieldsAndExistingStaticButtons() {
        UiComponent c = UiSchemaValidator.validateJson(form(input("name", "text") + ","
                + input("date", "date") + "," + input("count", "number")));
        assertEquals(3, c.children().size());
        assertDoesNotThrow(() -> UiSchemaValidator.validate(c));
        assertDoesNotThrow(() -> UiSchemaValidator.validateJson(
                "{\"type\":\"button\",\"text\":\"Next\",\"action\":\"Continue\"}"));
    }

    @Test
    void rejectsIncorrectTypesUnknownFieldsAndProps() {
        for (String json : new String[] {
                "{\"type\":\"imaginary\"}", "{\"type\":\"text\",\"text\":4}",
                "{\"type\":\"progress\",\"value\":1.1}", "{\"type\":\"list\",\"items\":\"not array\"}",
                "{\"type\":\"text\",\"script\":\"alert(1)\"}", "{\"type\":\"text\",\"props\":{\"url\":\"x\"}}",
                form("{\"type\":\"input\",\"props\":{\"name\":\"x\",\"required\":\"true\"}}"),
                "{\"type\":\"text\",\"type\":\"button\"}",
                "{\"type\":\"text\"} {\"type\":\"text\"}",
                form("{\"type\":\"input\",\"props\":{\"name\":\"x\",\"placeholder\":null}}"),
                "{\"type\":\"text\",\"children\":[{\"type\":\"text\"}]}",
                "{\"type\":\"table\",\"columns\":[\"A\"],\"rows\":[[\"A\",\"B\"]]}" }) {
            assertThrows(IllegalArgumentException.class, () -> UiSchemaValidator.validateJson(json), json);
        }
    }

    @Test
    void scopesNamesAndRejectsNestedOrUnscopedForms() {
        assertThrows(IllegalArgumentException.class, () -> UiSchemaValidator.validateJson(input("x", "text")));
        assertThrows(IllegalArgumentException.class, () -> UiSchemaValidator.validateJson(
                form(input("x", "text") + "," + input("x", "date"))));
        assertThrows(IllegalArgumentException.class, () -> UiSchemaValidator.validateJson(form(form(""))));
        assertDoesNotThrow(() -> UiSchemaValidator.validateJson(
                "{\"type\":\"column\",\"children\":[" + form(input("x", "text")) + ","
                        + form(input("x", "text")) + "]}"));
    }

    @Test
    void enforcesDepthNodeAndTextLimits() {
        String deep = "{\"type\":\"text\"}";
        for (int i = 0; i < 10; i++) {
            deep = "{\"type\":\"column\",\"children\":[" + deep + "]}";
        }
        String tooDeep = deep;
        assertThrows(IllegalArgumentException.class, () -> UiSchemaValidator.validateJson(tooDeep));
        assertThrows(IllegalArgumentException.class, () -> UiSchemaValidator.validateJson(
                "{\"type\":\"column\",\"children\":[" + "{\"type\":\"text\"},".repeat(200)
                        + "{\"type\":\"text\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> UiSchemaValidator.validateJson(
                "{\"type\":\"text\",\"text\":\"" + "x".repeat(8193) + "\"}"));
        assertThrows(IllegalArgumentException.class, () -> UiSchemaValidator.validateJson(" ".repeat(65_537)));
    }

    @Test
    void validatesRequiredDateAndNumberWithoutBookingClaims() {
        assertNotNull(FormSubmission.fieldError("text", " ", true));
        assertNotNull(FormSubmission.fieldError("date", "2026-02-30", true));
        assertNotNull(FormSubmission.fieldError("number", "NaN", true));
        assertNull(FormSubmission.fieldError("date", "2026-11-04", true));
        assertNull(FormSubmission.fieldError("number", "2.5", true));
        assertNull(FormSubmission.fieldError("number", "", false));
        Map<String, String> values = new LinkedHashMap<>();
        values.put("name", "A \"quoted\" name");
        values.put("date", "2026-11-04");
        String submission = FormSubmission.message("Find availability", values);
        assertTrue(submission.contains("\"name\":\"A \\\"quoted\\\" name\""));
        assertTrue(submission.contains("\"date\":\"2026-11-04\""));
        assertTrue(submission.contains("not confirmation of a booking"));
    }

    @Test
    void exposesErrorsForRepairWithoutRendering() {
        String bad = "```ui\n{\"type\":\"unknown\"}\n```";
        assertEquals(1, ReplyFormat.validate(bad, ReplySettings.Mode.AGENTIC_UI).size());
        assertTrue(ReplyFormat.validate(bad, ReplySettings.Mode.TEXT).isEmpty());
        assertEquals(1, ReplyFormat.validate("```ui\n{}", ReplySettings.Mode.AGENTIC_UI).size());
        assertInstanceOf(ReplySegment.Invalid.class, ReplyFormat.parse(bad, ReplySettings.Mode.AGENTIC_UI).get(0));
    }
}
