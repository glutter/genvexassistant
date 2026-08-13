import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EveningCoolingPolicyTest {
    @Test
    void startsAtSpeedTwoForObservedEveningTemperatures() {
        assertEquals(2, EveningCoolingPolicy.selectSpeed(0, 19.1, 20.0, 24.0, 22.0, 22.5, 15.0, false));
    }

    @Test
    void doesNotCoolWithSupplyAirBelowComfortFloor() {
        assertEquals(0, EveningCoolingPolicy.selectSpeed(0, 14.9, 20.0, 24.0, 22.0, 22.5, 15.0, false));
        assertEquals(0, EveningCoolingPolicy.selectSpeed(0, 15.0, 20.0, 24.0, 22.0, 22.5, 15.0, false));
    }

    @Test
    void stopsWhenOutsideAndIndoorTemperaturesConverge() {
        assertEquals(0, EveningCoolingPolicy.selectSpeed(2, 21.5, 21.2, 22.1, 22.0, 22.5, 15.0, false));
    }

    @Test
    void continuesThroughStartDeadbandToPreventSpeedOscillation() {
        assertEquals(2, EveningCoolingPolicy.selectSpeed(2, 20.0, 20.5, 22.2, 22.0, 22.5, 15.0, false));
    }

    @Test
    void escalatesToSpeedThreeWhenCoolingStalls() {
        assertEquals(3, EveningCoolingPolicy.selectSpeed(2, 19.1, 20.0, 24.0, 22.0, 22.5, 15.0, true));
        assertEquals(3, EveningCoolingPolicy.selectSpeed(3, 19.1, 20.0, 24.0, 22.0, 22.5, 15.0, true));
    }

    @Test
    void measuresProgressFromIndoorCoolingNotChangingOutsideTemperature() {
        assertFalse(EveningCoolingPolicy.hasStalled(24.0, 23.6, 30 * 60_000L, 30 * 60_000L, 0.3));
        assertTrue(EveningCoolingPolicy.hasStalled(24.0, 23.8, 30 * 60_000L, 30 * 60_000L, 0.3));
    }

    @Test
    void keepsSpeedThreeNearImmediateThresholds() {
        assertEquals(3, EveningCoolingPolicy.selectSpeed(3, 19.0, 18.6, 24.2, 22.0, 22.5, 15.0, false));
    }

    @Test
    void respectsConfigurableStartAndStopTemperatures() {
        assertEquals(0, EveningCoolingPolicy.selectSpeed(0, 20.0, 20.0, 23.9, 23.0, 24.0, 15.0, false));
        assertEquals(2, EveningCoolingPolicy.selectSpeed(0, 20.0, 20.0, 24.0, 23.0, 24.0, 15.0, false));
        assertEquals(2, EveningCoolingPolicy.selectSpeed(2, 20.0, 20.0, 23.1, 23.0, 24.0, 15.0, false));
        assertEquals(0, EveningCoolingPolicy.selectSpeed(2, 20.0, 20.0, 23.0, 23.0, 24.0, 15.0, false));
    }

    @Test
    void rejectsAnInvertedTriggerRange() {
        assertEquals(0, EveningCoolingPolicy.selectSpeed(0, 19.0, 20.0, 24.0, 24.0, 23.0, 15.0, false));
    }
}