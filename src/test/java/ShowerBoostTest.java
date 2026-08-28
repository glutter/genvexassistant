import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShowerBoostTest {
    @Test
    void humidityAboveBaselineDoesNotEndShowerBoost() {
        assertFalse(HumidityMonitor.shouldDeactivateBoost(46, 45.0));
    }

    @Test
    void showerBoostEndsAtOrBelowBaselineWithoutAMinimumDuration() {
        assertTrue(HumidityMonitor.shouldDeactivateBoost(45, 45.0));
        assertTrue(HumidityMonitor.shouldDeactivateBoost(44, 45.0));
    }
}
