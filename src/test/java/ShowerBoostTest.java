import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShowerBoostTest {
    private static final double NO_MOISTURE = Double.NaN;

    @Test
    void humidityAboveBaselineDoesNotEndShowerBoost() {
        assertFalse(HumidityMonitor.shouldDeactivateBoost(46, 45.0, NO_MOISTURE, NO_MOISTURE));
    }

    @Test
    void showerBoostEndsAtOrBelowBaselineWithoutAMinimumDuration() {
        assertTrue(HumidityMonitor.shouldDeactivateBoost(45, 45.0, NO_MOISTURE, NO_MOISTURE));
        assertTrue(HumidityMonitor.shouldDeactivateBoost(44, 45.0, NO_MOISTURE, NO_MOISTURE));
    }

    @Test
    void moistureDecidesRecoveryWhenBothItAndItsBaselineAreAvailable() {
        // 8.71 g/kg is 20 C at 60 % RH. The relative humidity argument is deliberately inconsistent with
        // the mixing ratio here, to prove which one the decision is actually taken on.
        assertTrue(HumidityMonitor.shouldDeactivateBoost(70, 45.0, 8.71, 8.71));
        assertTrue(HumidityMonitor.shouldDeactivateBoost(70, 45.0, 8.60, 8.71));
        assertFalse(HumidityMonitor.shouldDeactivateBoost(40, 45.0, 8.80, 8.71));
    }

    @Test
    void aHouseThatCooledDuringTheShowerStillRecovers() {
        // The reason this changed at all: after a 1 C drop the reading is nearly 4 points higher at the same
        // moisture, so the relative-humidity test keeps the fan at boost speed in the cold for hours.
        double baseline = HumidityPhysics.mixingRatioGramsPerKg(60, 20.0);
        double afterCooling = HumidityPhysics.mixingRatioGramsPerKg(63, 19.0);
        assertTrue(afterCooling < baseline, "63 % at 19 C is drier air than 60 % at 20 C");

        assertTrue(HumidityMonitor.shouldDeactivateBoost(63, 60.0, afterCooling, baseline));
        assertFalse(HumidityMonitor.shouldDeactivateBoost(63, 60.0, NO_MOISTURE, baseline));
    }

    @Test
    void aBoostRestoredFromDiskFallsBackToTheRelativeHumidityBaseline() {
        // The persisted boost_baseline column stays a percentage so that an older binary reading the same
        // database still behaves, which leaves a restored boost with no moisture baseline at all.
        assertFalse(HumidityMonitor.shouldDeactivateBoost(46, 45.0, 8.71, NO_MOISTURE));
        assertTrue(HumidityMonitor.shouldDeactivateBoost(45, 45.0, 8.71, NO_MOISTURE));
    }

    @Test
    void withoutAnyUsableBaselineTheBoostIsNeverEndedByAccident() {
        assertFalse(HumidityMonitor.shouldDeactivateBoost(45, Double.NaN, NO_MOISTURE, NO_MOISTURE));
        assertFalse(HumidityMonitor.shouldDeactivateBoost(45, Double.NaN, 8.71, NO_MOISTURE));
    }
}
