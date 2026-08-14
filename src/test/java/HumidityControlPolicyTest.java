import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
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

        assertFalse(HumidityMonitor.shouldDeactivateBoost(10 * 60 * 1000L, initialDurationEnd,
            45, 45.0, 1));
        assertFalse(HumidityMonitor.shouldDeactivateBoost(initialDurationEnd + 1, initialDurationEnd,
                52, 45.0, 1));
        assertTrue(HumidityMonitor.shouldDeactivateBoost(initialDurationEnd + 1, initialDurationEnd,
                46, 45.0, 1));
        }

        @Test
        void recoveryNeverLowersAnActiveCoolingTarget() {
        assertEquals(3, HumidityMonitor.selectCombinedRecoverySpeed(47, 45.0, 4, 3,
            1, 30, 65, 80, 3));
        assertEquals(3, HumidityMonitor.selectCombinedRecoverySpeed(49, 45.0, 4, 3,
            1, 30, 65, 80, 2));
        }

        @Test
        void candidateBaselineStaysFixedDuringGradualRise() {
        double candidate = Double.NaN;
        candidate = HumidityMonitor.updateHumidityRiseCandidate(candidate, 45, 46, 45.0);
        assertEquals(45.0, candidate);
        candidate = HumidityMonitor.updateHumidityRiseCandidate(candidate, 46, 46, 45.1);
        assertEquals(45.0, candidate);
        candidate = HumidityMonitor.updateHumidityRiseCandidate(candidate, 46, 47, 45.2);
        assertEquals(45.0, candidate);
        candidate = HumidityMonitor.updateHumidityRiseCandidate(candidate, 47, 48, 45.5);
        assertEquals(45.0, candidate);
        candidate = HumidityMonitor.updateHumidityRiseCandidate(candidate, 48, 49, 46.0);
        assertEquals(45.0, candidate);
        assertTrue(HumidityMonitor.hasHumidityRise(49, candidate, 4));
        assertTrue(Double.isNaN(HumidityMonitor.updateHumidityRiseCandidate(candidate, 49, 45, 46.0)));
    }

    @Test
    void persistsActiveRecoveryStateForRestart() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            HumidityMonitor.ensureControlStateTable(connection);
            HumidityMonitor.ControlState expected = new HumidityMonitor.ControlState(
                    true, 45.0, 1_000L, 2_000L, 44.5);

            HumidityMonitor.saveControlState(connection, expected);
            HumidityMonitor.ControlState restored = HumidityMonitor.loadControlState(connection);

            assertEquals(expected, restored);
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM control_state")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
    }

        @Test
        void rejectsInvalidHumidityRecoveryConfiguration() {
        assertThrows(IllegalArgumentException.class, () ->
            HumidityMonitor.validateControlConfiguration(0, 30, 1, 15 * 60_000L,
                3, 1, 30, 65, 80));
        assertThrows(IllegalArgumentException.class, () ->
            HumidityMonitor.validateControlConfiguration(4, 0, 1, 15 * 60_000L,
                3, 1, 30, 65, 80));
        assertThrows(IllegalArgumentException.class, () ->
            HumidityMonitor.validateControlConfiguration(4, 30, -1, 15 * 60_000L,
                3, 1, 30, 65, 80));
        assertThrows(IllegalArgumentException.class, () ->
            HumidityMonitor.validateControlConfiguration(4, 30, 1, 0,
                3, 1, 30, 65, 80));
        assertThrows(IllegalArgumentException.class, () ->
            HumidityMonitor.validateControlConfiguration(4, 30, 1, 15 * 60_000L,
                5, 1, 30, 65, 80));
        assertThrows(IllegalArgumentException.class, () ->
            HumidityMonitor.validateControlConfiguration(4, 30, 1, 15 * 60_000L,
                3, 1, 65, 30, 80));
        }
}