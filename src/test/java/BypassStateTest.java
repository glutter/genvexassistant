import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BypassStateTest {
    @Test
    void normalizesClosedOpenAndUnavailableStates() {
        assertEquals(0, HumidityMonitor.normalizeBypassState(0));
        assertEquals(1, HumidityMonitor.normalizeBypassState(1));
        assertEquals(1, HumidityMonitor.normalizeBypassState(100));
        assertEquals(-1, HumidityMonitor.normalizeBypassState(-1));
    }

    @Test
    void serializesBypassStateConsistentlyAsBooleanOrNull() {
        assertEquals("false", HumidityMonitor.jsonBypassState(0));
        assertEquals("true", HumidityMonitor.jsonBypassState(1));
        assertEquals("null", HumidityMonitor.jsonBypassState(-1));
    }
}