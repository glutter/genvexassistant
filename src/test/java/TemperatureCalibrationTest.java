import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TemperatureCalibrationTest {
    @Test
    void appliesSharedOffsetToReportedMonitorTemperatures() {
        assertEquals(18.4, HumidityMonitor.rawTemperature(484, -300));
        assertEquals(24.0, HumidityMonitor.rawTemperature(540, -300));
        assertEquals(17.2, HumidityMonitor.rawTemperature(472, -300));
        assertEquals(19.7, HumidityMonitor.rawTemperature(497, -300));
    }

    @Test
    void preservesUnavailableDatapointSentinel() {
        assertTrue(Double.isNaN(HumidityMonitor.rawTemperature(-1, -300)));
    }
}