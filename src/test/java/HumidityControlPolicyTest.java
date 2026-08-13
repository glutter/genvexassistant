import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HumidityControlPolicyTest {
    @Test
    void usesLowSpeedAtAndBelowLowThreshold() {
        assertEquals(1, HumidityMonitor.selectHumiditySpeed(29, 30, 65, 3));
        assertEquals(1, HumidityMonitor.selectHumiditySpeed(30, 30, 65, 3));
    }

    @Test
    void usesConfiguredNormalSpeedBetweenThresholds() {
        assertEquals(3, HumidityMonitor.selectHumiditySpeed(31, 30, 65, 3));
        assertEquals(3, HumidityMonitor.selectHumiditySpeed(64, 30, 65, 3));
    }

    @Test
    void usesHighSpeedAtAndAboveHighThreshold() {
        assertEquals(2, HumidityMonitor.selectHumiditySpeed(65, 30, 65, 1));
        assertEquals(2, HumidityMonitor.selectHumiditySpeed(80, 30, 65, 1));
    }

    @Test
    void highHumidityDoesNotLowerConfiguredNormalSpeed() {
        assertEquals(3, HumidityMonitor.selectHumiditySpeed(64, 30, 65, 3));
        assertEquals(3, HumidityMonitor.selectHumiditySpeed(65, 30, 65, 3));
    }

    @Test
    void highHumidityOverridesQuietNightWhenCoolingIsUnavailable() {
        assertEquals(2, HumidityMonitor.selectAutomaticSpeed(65, true, 0, 30, 65, 1));
        assertEquals(2, HumidityMonitor.selectAutomaticSpeed(79, true, 0, 30, 65, 1));
    }

    @Test
    void quietNightStillUsesSpeedOneAtNormalHumidity() {
        assertEquals(1, HumidityMonitor.selectAutomaticSpeed(49, true, 0, 30, 65, 2));
    }

    @Test
    void coolingDoesNotLowerAHigherHumidityTarget() {
        assertEquals(3, HumidityMonitor.selectAutomaticSpeed(70, false, 2, 30, 65, 3));
    }
}