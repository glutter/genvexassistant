import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DefrostStateTest {
    @Test
    void detectsActiveDefrost() {
        assertEquals(HumidityMonitor.DefrostState.ACTIVE,
                HumidityMonitor.detectDefrostState(50, 700, 8.0));
    }

    @Test
    void treatsMissingExtractRpmAsUnknownWhenDefrostIsPossible() {
        assertEquals(HumidityMonitor.DefrostState.UNKNOWN,
                HumidityMonitor.detectDefrostState(50, -1, 8.0));
    }

    @Test
    void rulesOutDefrostWhenSupplyFanRunsOrSupplyIsWarm() {
        assertEquals(HumidityMonitor.DefrostState.INACTIVE,
                HumidityMonitor.detectDefrostState(200, -1, 8.0));
        assertEquals(HumidityMonitor.DefrostState.INACTIVE,
                HumidityMonitor.detectDefrostState(50, -1, 12.0));
    }

    @Test
    void reportsUnknownSeparatelyFromActiveDefrost() {
        assertEquals(" [DEFROSTING]",
                HumidityMonitor.defrostStatusSuffix(HumidityMonitor.DefrostState.ACTIVE));
        assertEquals(" [DEFROST UNKNOWN]",
                HumidityMonitor.defrostStatusSuffix(HumidityMonitor.DefrostState.UNKNOWN));
        assertEquals("",
                HumidityMonitor.defrostStatusSuffix(HumidityMonitor.DefrostState.INACTIVE));
    }
}
