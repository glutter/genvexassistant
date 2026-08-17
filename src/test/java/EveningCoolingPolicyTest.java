import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class EveningCoolingPolicyTest {
    @Test
    void startsAtSpeedTwoForObservedEveningTemperatures() {
        assertEquals(2, EveningCoolingPolicy.selectSpeed(0, 19.1, 20.0, 24.0, 22.0, 22.5, 15.0, true, false));
    }

    @Test
    void startsAtSpeedTwoForDashboardTemperatures() {
        assertEquals(2, EveningCoolingPolicy.selectSpeed(0, 23.1, 16.7, 23.7, 22.0, 22.5, 15.0, true, false));
    }

    @Test
    void doesNotCoolWithSupplyAirBelowComfortFloor() {
        assertEquals(0, EveningCoolingPolicy.selectSpeed(0, 14.9, 20.0, 24.0, 22.0, 22.5, 15.0, true, false));
        assertEquals(0, EveningCoolingPolicy.selectSpeed(0, 15.0, 20.0, 24.0, 22.0, 22.5, 15.0, true, false));
    }

    @Test
    void stopsWhenOutsideAndIndoorTemperaturesConverge() {
        assertEquals(0, EveningCoolingPolicy.selectSpeed(2, 21.5, 21.2, 22.1, 22.0, 22.5, 15.0, true, false));
    }

    @Test
    void continuesThroughStartDeadbandToPreventSpeedOscillation() {
        assertEquals(2, EveningCoolingPolicy.selectSpeed(2, 20.0, 20.5, 22.2, 22.0, 22.5, 15.0, true, false));
    }

    @Test
    void escalatesToSpeedThreeWhenCoolingStalls() {
        assertEquals(3, EveningCoolingPolicy.selectSpeed(2, 19.1, 20.0, 24.0, 22.0, 22.5, 15.0, true, true));
        assertEquals(3, EveningCoolingPolicy.selectSpeed(3, 19.1, 20.0, 24.0, 22.0, 22.5, 15.0, true, true));
    }

    @Test
    void measuresProgressFromIndoorCoolingNotChangingOutsideTemperature() {
        assertFalse(EveningCoolingPolicy.hasStalled(24.0, 23.6, 30 * 60_000L, 30 * 60_000L, 0.3));
        assertTrue(EveningCoolingPolicy.hasStalled(24.0, 23.8, 30 * 60_000L, 30 * 60_000L, 0.3));
    }

    @Test
    void keepsSpeedThreeLatchedUntilCoolingStops() {
        assertEquals(3, EveningCoolingPolicy.selectSpeed(3, 19.0, 18.6, 24.2, 22.0, 22.5, 15.0, true, false));
    }

    @Test
    void largeTemperatureDifferenceStillStartsAtSpeedTwo() {
        assertEquals(2, EveningCoolingPolicy.selectSpeed(0, 19.0, 16.0, 24.2, 22.0, 22.5, 15.0, true, false));
    }

    @Test
    void respectsConfigurableStartAndStopTemperatures() {
        assertEquals(0, EveningCoolingPolicy.selectSpeed(0, 20.0, 20.0, 23.9, 23.0, 24.0, 15.0, true, false));
        assertEquals(2, EveningCoolingPolicy.selectSpeed(0, 20.0, 20.0, 24.0, 23.0, 24.0, 15.0, true, false));
        assertEquals(2, EveningCoolingPolicy.selectSpeed(2, 20.0, 20.0, 23.1, 23.0, 24.0, 15.0, true, false));
        assertEquals(0, EveningCoolingPolicy.selectSpeed(2, 20.0, 20.0, 23.0, 23.0, 24.0, 15.0, true, false));
    }

    @Test
    void rejectsAnInvertedTriggerRange() {
        assertEquals(0, EveningCoolingPolicy.selectSpeed(0, 19.0, 20.0, 24.0, 24.0, 23.0, 15.0, true, false));
    }

    @Test
    void requiresAnOpenBypass() {
        assertEquals(0, EveningCoolingPolicy.selectSpeed(0, 20.0, 14.0, 23.2, 22.0, 22.5, 15.0, false, false));
        assertEquals(0, EveningCoolingPolicy.selectSpeed(2, 20.0, 14.0, 23.2, 22.0, 22.5, 15.0, false, false));
    }

    @Test
    void doesNotCoolBelowOutdoorSafetyFloor() {
        assertEquals(0, EveningCoolingPolicy.selectSpeed(0, 16.0, 9.9, 23.2, 22.0, 22.5, 15.0, true, false));
        assertEquals(2, EveningCoolingPolicy.selectSpeed(0, 16.0, 10.0, 23.2, 22.0, 22.5, 15.0, true, false));
    }

    @Test
    void doesNotCoolBelowIndoorSafetyFloor() {
        assertEquals(0, EveningCoolingPolicy.selectSpeed(0, 16.0, 14.0, 20.9, 20.0, 20.5, 15.0, true, false));
        assertEquals(2, EveningCoolingPolicy.selectSpeed(0, 16.0, 14.0, 21.0, 20.0, 20.5, 15.0, true, false));
    }

    @Test
    void fallbackCoolingWindowContinuesAfterMidnight() {
        LocalTime start = LocalTime.of(18, 0);
        LocalTime end = LocalTime.of(6, 30);

        assertTrue(HumidityMonitor.isCoolingFallbackWindow(LocalTime.of(0, 10), start, end));
        assertFalse(HumidityMonitor.isCoolingFallbackWindow(LocalTime.NOON, start, end));
    }
}
