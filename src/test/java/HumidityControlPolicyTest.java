import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;

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

    @Test
    void boostSpeedTracksHumidityDeltaAboveHistoricalBaseline() {
        assertEquals(1, HumidityMonitor.selectDynamicBoostSpeed(46, 45.0, 4, 3, 1));
        assertEquals(2, HumidityMonitor.selectDynamicBoostSpeed(47, 45.0, 4, 3, 1));
        assertEquals(3, HumidityMonitor.selectDynamicBoostSpeed(49, 45.0, 4, 3, 1));
        assertEquals(4, HumidityMonitor.selectDynamicBoostSpeed(53, 45.0, 4, 4, 1));
    }

    @Test
    void historicalDeltaDetectsAGradualRiseAndOverridesNightSpeed() {
        assertTrue(HumidityMonitor.hasHumidityRise(49, 45.0, 4));
        assertEquals(3, HumidityMonitor.selectBoostRecoverySpeed(49, 45.0, 4, 3,
                1, 30, 65, 80));
    }

    @Test
    void recoveryNeverUndercutsAbsoluteHumidityProtection() {
        assertEquals(2, HumidityMonitor.selectBoostRecoverySpeed(65, 64.0, 4, 3,
                1, 30, 65, 80));
        assertEquals(3, HumidityMonitor.selectBoostRecoverySpeed(80, 78.0, 4, 2,
                1, 30, 65, 80));
    }

    @Test
    void historicalBaselineUsesOnlyReadingsBeforeTheRise() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE humidity_readings (timestamp DATETIME, humidity INTEGER)");
            statement.execute("INSERT INTO humidity_readings VALUES "
                    + "('2026-08-14 09:35:00', 44), ('2026-08-14 09:45:00', 46), "
                    + "('2026-08-14 10:00:00', 55), ('2026-08-14 09:00:00', 80)");

            double baseline = HumidityMonitor.historicalHumidityAverage(connection,
                    Instant.parse("2026-08-14T10:00:00Z"), 30);

            assertEquals(45.0, baseline);
        }
    }

    @Test
    void boostContinuesPastInitialDurationUntilHistoricalBaselineIsRecovered() {
        long initialDurationEnd = 15 * 60 * 1000L;

        assertFalse(HumidityMonitor.shouldDeactivateBoost(initialDurationEnd + 1, 10 * 60 * 1000L,
                52, 45.0, 1));
        assertTrue(HumidityMonitor.shouldDeactivateBoost(initialDurationEnd + 1, 10 * 60 * 1000L,
                46, 45.0, 1));
        assertFalse(HumidityMonitor.shouldDeactivateBoost(9 * 60 * 1000L, 10 * 60 * 1000L,
                45, 45.0, 1));
    }
}