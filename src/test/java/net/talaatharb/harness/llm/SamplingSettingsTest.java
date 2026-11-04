package net.talaatharb.harness.llm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class SamplingSettingsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void missingAndBlankOptionsLeaveDefaultsUnsetAndExplicitOptionsRoundTrip() throws Exception {
        ModelSettings legacy = mapper.readValue("{\"model\":\"legacy\"}", ModelSettings.class);
        assertNull(legacy.temperature);
        assertNull(legacy.seed);
        assertNull(ModelSettings.parseTemperature("  "));
        assertNull(ModelSettings.parseSeed(""));
        assertEquals(0.0, ModelSettings.parseTemperature(" 0 "));
        assertEquals((long) Integer.MIN_VALUE, ModelSettings.parseSeed("-2147483648"));
        legacy.temperature = 0.35;
        legacy.seed = 42L;
        ModelSettings restored = mapper.readValue(mapper.writeValueAsString(legacy), ModelSettings.class);
        assertEquals(legacy.temperature, restored.temperature);
        assertEquals(legacy.seed, restored.seed);
    }

    @Test
    void invalidValuesAndUnsupportedSeedAreExplicit() throws Exception {
        for (String text : new String[] { "NaN", "Infinity", "-0.1", "warm", "1e400" }) {
            assertThrows(IllegalArgumentException.class, () -> ModelSettings.parseTemperature(text), text);
        }
        for (String text : new String[] { "1.5", "9223372036854775808", "-9223372036854775809", "random" }) {
            assertThrows(IllegalArgumentException.class, () -> ModelSettings.parseSeed(text), text);
        }
        ModelSettings s = new ModelSettings();
        for (String provider : new String[] { "ollama", "openai", "anthropic" }) {
            for (double invalid : new double[] { -1, Double.NaN, Double.POSITIVE_INFINITY }) {
                s.temperature = invalid;
                assertThrows(ModelProviderException.class, () -> s.validateSampling(provider));
            }
        }
        s.temperature = 1.1;
        assertThrows(ModelProviderException.class, () -> s.validateSampling("anthropic"));
        s.temperature = 2.1;
        assertThrows(ModelProviderException.class, () -> s.validateSampling("openai"));
        s.validateSampling("ollama");
        s.temperature = null;
        s.seed = 1L;
        assertTrue(assertThrows(ModelProviderException.class, () -> s.validateSampling("anthropic"))
                .getMessage().contains("does not support seed"));
        s.validateSampling("openai");
        s.validateSampling("ollama");
        s.seed = 2147483648L;
        assertThrows(ModelProviderException.class, () -> s.validateSampling("ollama"));
        s.validateSampling("openai");
    }
}
