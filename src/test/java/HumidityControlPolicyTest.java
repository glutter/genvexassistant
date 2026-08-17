import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

class HumidityControlPolicyTest {
    private static final HumidityMonitor.HumidityPolicy DEFAULT_POLICY =
            new HumidityMonitor.HumidityPolicy(4, 1, 3, 1, 30, 65, 80);

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
    void nightSpeedIsLimitedWithoutALargeHumidityDelta() {
        assertEquals(2, HumidityMonitor.limitNightSpeed(3, true, 80,
                Double.NaN, DEFAULT_POLICY));
        assertEquals(2, HumidityMonitor.limitNightSpeed(3, true, 53,
                50.0, DEFAULT_POLICY));
    }

    @Test
    void largeHumidityDeltaCanExceedNightLimit() {
        assertEquals(3, HumidityMonitor.limitNightSpeed(3, true, 54,
                50.0, DEFAULT_POLICY));
    }

    @Test
    void nightLimitDoesNotApplyDuringTheDay() {
        assertEquals(3, HumidityMonitor.limitNightSpeed(3, false, 50,
                Double.NaN, DEFAULT_POLICY));
    }

    @Test
    void acknowledgedCommandIsNotRepeatedDuringItsGracePeriod() {
        assertFalse(HumidityMonitor.shouldSendFanCommand(2, 1, 2,
                false, 59_999, 0, 60_000));
        assertTrue(HumidityMonitor.shouldSendFanCommand(2, 1, 2,
                false, 60_000, 0, 60_000));
        assertTrue(HumidityMonitor.shouldSendFanCommand(3, 2, 2,
                false, 1, 0, 60_000));
        assertTrue(HumidityMonitor.shouldSendFanCommand(2, 2, 2,
                true, 1, 0, 60_000));
    }

    @Test
        void humidityPhasesHavePredictableSpeeds() {
        assertEquals(3, HumidityMonitor.selectHumidityRecoverySpeed(60, 50.0,
            DEFAULT_POLICY, 0, HumidityMonitor.HumidityRecoveryPhase.BOOST));
        assertEquals(2, HumidityMonitor.selectHumidityRecoverySpeed(60, 50.0,
            DEFAULT_POLICY, 0, HumidityMonitor.HumidityRecoveryPhase.RECOVERY));
        assertEquals(1, HumidityMonitor.selectHumidityRecoverySpeed(53, 50.0,
            DEFAULT_POLICY, 0, HumidityMonitor.HumidityRecoveryPhase.RECOVERY));
    }

    @Test
    void configuredDeltaTriggersAtTheBoundary() {
        assertFalse(HumidityMonitor.hasHumidityRise(48, 45.0, DEFAULT_POLICY));
        assertTrue(HumidityMonitor.hasHumidityRise(49, 45.0, DEFAULT_POLICY));
    }

    @Test
    void recoveryNeverUndercutsAbsoluteHumidityProtection() {
        assertEquals(2, HumidityMonitor.selectHumidityRecoverySpeed(65, 64.0,
            DEFAULT_POLICY, 0, HumidityMonitor.HumidityRecoveryPhase.RECOVERY));
        HumidityMonitor.HumidityPolicy limitedBoost =
            new HumidityMonitor.HumidityPolicy(4, 1, 2, 1, 30, 65, 80);
        assertEquals(3, HumidityMonitor.selectHumidityRecoverySpeed(80, 78.0,
            limitedBoost, 0, HumidityMonitor.HumidityRecoveryPhase.RECOVERY));
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
    void boostStopsAtRiseThresholdInsteadOfHistoricalBaseline() {
        long initialDurationEnd = 15 * 60 * 1000L;

        assertFalse(HumidityMonitor.shouldDeactivateBoost(10 * 60 * 1000L, initialDurationEnd,
            45, 45.0, DEFAULT_POLICY));
        assertFalse(HumidityMonitor.shouldDeactivateBoost(initialDurationEnd + 1, initialDurationEnd,
            50, 45.0, DEFAULT_POLICY));
        assertTrue(HumidityMonitor.shouldDeactivateBoost(initialDurationEnd + 1, initialDurationEnd,
            48, 45.0, DEFAULT_POLICY));
        assertEquals(49.0, HumidityMonitor.humidityRecoveryTarget(46.0, DEFAULT_POLICY));
        }

    @Test
    void pendingBoostCannotDeactivateBeforeFanControlStarts() {
        assertFalse(HumidityMonitor.shouldDeactivateBoost(10_000, 0,
                45, 45.0, DEFAULT_POLICY));
    }

    @Test
    void recoveryNeverLowersAnActiveCoolingTarget() {
        assertEquals(3, HumidityMonitor.selectHumidityRecoverySpeed(47, 45.0,
            DEFAULT_POLICY, 3, HumidityMonitor.HumidityRecoveryPhase.RECOVERY));
        assertEquals(2, HumidityMonitor.selectHumidityRecoverySpeed(49, 45.0,
            DEFAULT_POLICY, 2, HumidityMonitor.HumidityRecoveryPhase.RECOVERY));
        }

    @Test
    void stableHumidityDoesNotStartRecoveryButASpikeDoes() {
        assertFalse(HumidityMonitor.hasHumidityRise(50, 50.0, DEFAULT_POLICY));
        assertTrue(HumidityMonitor.hasHumidityRise(54, 50.0, DEFAULT_POLICY));
        assertTrue(HumidityMonitor.hasHumidityRise(60, 50.0, DEFAULT_POLICY));
    }

    @Test
    void persistsActiveRecoveryStateForRestart() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            HumidityMonitor.ensureControlStateTable(connection);
            HumidityMonitor.ControlState expected = new HumidityMonitor.ControlState(
                    true, 45.0, 2_000L);

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
    void activeRecoveryCanBeSuspendedWithoutDiscardingItsPersistedState() {
        HumidityMonitor.ControlState active = new HumidityMonitor.ControlState(true, 45.0, 2_000L);

        assertEquals(active, HumidityMonitor.restorableControlState(true, active));
        assertFalse(HumidityMonitor.restorableControlState(false, active).boostActive());
    }

    @Test
    void legacyRecoveryColumnsRemainCompatible() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE control_state (id INTEGER PRIMARY KEY, "
                    + "boost_active INTEGER NOT NULL DEFAULT 0, boost_baseline REAL, "
                    + "boost_min_end INTEGER NOT NULL DEFAULT 0, "
                    + "boost_end INTEGER NOT NULL DEFAULT 0, rise_candidate REAL)");
            statement.execute("INSERT INTO control_state (id) VALUES (1)");
            HumidityMonitor.ControlState expected = new HumidityMonitor.ControlState(
                    true, 47.0, 3_000L);

            HumidityMonitor.saveControlState(connection, expected);

            assertEquals(expected, HumidityMonitor.loadControlState(connection));
        }
    }

    @Test
    void newRecoverySchemaSupportsRollbackToLegacyQueries() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            HumidityMonitor.ensureControlStateTable(connection);

            statement.executeUpdate("UPDATE control_state SET boost_min_end = 1000, "
                    + "rise_candidate = 45.0 WHERE id = 1");

            try (ResultSet result = statement.executeQuery(
                    "SELECT boost_min_end, rise_candidate FROM control_state WHERE id = 1")) {
                assertTrue(result.next());
                assertEquals(1_000L, result.getLong("boost_min_end"));
                assertEquals(45.0, result.getDouble("rise_candidate"));
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

    @Test
    void legacyToleranceAboveRiseThresholdIsCapped() {
        HumidityMonitor.HumidityPolicy legacyPolicy =
                new HumidityMonitor.HumidityPolicy(4, 5, 3, 1, 30, 65, 80);

        assertEquals(46.0, HumidityMonitor.humidityRecoveryTarget(46.0, legacyPolicy));
    }

    @Test
    void rejectsUnsafeRuntimeConfiguration() {
        assertThrows(IllegalArgumentException.class, () ->
                HumidityMonitor.validateRuntimeConfiguration(0, 60_000,
                        LocalTime.of(22, 0), LocalTime.of(6, 30), 22.5, 22.0, 15.0));
        assertThrows(IllegalArgumentException.class, () ->
                HumidityMonitor.validateRuntimeConfiguration(30, 0,
                        LocalTime.of(22, 0), LocalTime.of(6, 30), 22.5, 22.0, 15.0));
        assertThrows(IllegalArgumentException.class, () ->
                HumidityMonitor.validateRuntimeConfiguration(30, 60_000,
                        LocalTime.of(22, 0), LocalTime.of(22, 0), 22.5, 22.0, 15.0));
        assertThrows(IllegalArgumentException.class, () ->
                HumidityMonitor.validateRuntimeConfiguration(30, 60_000,
                        LocalTime.of(22, 0), LocalTime.of(6, 30), 21.5, 22.0, 15.0));
    }
}
