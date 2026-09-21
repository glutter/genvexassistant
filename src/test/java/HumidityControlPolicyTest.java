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
        @Test
        void liveTelemetryUsesSuccessfulDeviceTimeAndRejectsStaleOrFailedProgress() {
                HumidityMonitor monitor = new HumidityMonitor("unused", "unused");
                long now = Instant.parse("2026-09-21T12:00:00Z").toEpochMilli();
                assertTrue(monitor.liveJson(now).contains("\"sampled_at\":null"));
                assertTrue(monitor.liveJson(now).contains("\"moisture\":null"));
                assertEquals(75, HumidityMonitor.staleAfterSeconds(30));
                assertEquals(150, HumidityMonitor.staleAfterSeconds(60));
                HumidityMonitor.ControlDecision decision = new HumidityMonitor.ControlDecision(
                                "Gentle humidity recovery + Heat-loss guard", 2, 1);
                monitor.recordSuccessfulTelemetry(decision, 60, 20, 50, 7, Instant.ofEpochMilli(now));
                String live = monitor.liveJson(now + 1000);
                assertTrue(live.contains("\"sampled_at\":\"2026-09-21T12:00:00Z\""));
                assertTrue(live.contains("\"humidity_delta\":10.000"));
                assertTrue(live.contains("\"policy_speed\":2, \"target_speed\":1"));
                assertTrue(live.contains("\"moisture_change_30m\":null"));
                String stale = monitor.liveJson(now + 76_000);
                assertTrue(stale.contains("device data stale"));
                assertTrue(stale.contains("\"moisture\":null"));
                monitor.recordFailedPoll();
                assertTrue(monitor.liveJson(now).contains("\"device_poll_failed\":true"));
                String failed = monitor.liveJson(now + 2000);
                assertTrue(failed.contains("device poll failed"));
                assertTrue(failed.contains("\"sampled_at\":\"2026-09-21T12:00:00Z\""));
                assertTrue(failed.contains("\"moisture_change_30m\":null"));
                monitor.recordSuccessfulTelemetry(decision, 60, Double.NaN, 50, 7, Instant.ofEpochMilli(now + 30_000));
                assertTrue(monitor.liveJson(now + 30_000).contains("\"moisture\":null"));
                assertTrue(monitor.liveJson(now + 30_000).contains("\"moisture_baseline\":null"));
        }

        @Test
        void moistureTrendRequiresContinuousCoverageAndUsesSignedNowMinusPast() {
                HumidityMonitor.MoistureTrend trend = new HumidityMonitor.MoistureTrend(75_000);
                for (int sample = 0; sample < 60; sample++) {
                        assertTrue(Double.isNaN(trend.add(sample * 30_000L, 10 - sample * 0.01)));
                }
                assertEquals(-0.6, trend.add(1_800_000, 9.4), 0.00001);
                assertTrue(Double.isNaN(trend.add(1_900_000, 9.3)));
                assertTrue(Double.isNaN(trend.add(1_930_000, 9.2)));
                trend.reset();
                for (int sample = 0; sample <= 60; sample++) trend.add(sample * 30_000L, 9);
                assertEquals(0.5, trend.add(1_830_000, 9.5), 0.00001);
                assertTrue(Double.isNaN(trend.add(1_860_000, Double.NaN)));
                assertTrue(Double.isNaN(trend.add(1_890_000, 9.4)));
        }

        @Test
        void reasonsAreJsonEscapedAndDefrostIsNotFirmwareConfirmed() {
                assertEquals("\"quoted \\\"reason\\\" \\\\ \\u000a\\u0009\\u0000\"",
                                HumidityMonitor.jsonString("quoted \"reason\" \\ \n\t\0"));
                assertEquals("null", HumidityMonitor.jsonString(null));
                HumidityMonitor.ControlDecision decision = new HumidityMonitor.ControlDecision("Normal", 1, 1);
                assertTrue(HumidityMonitor.withDefrostReason(decision, HumidityMonitor.DefrostState.ACTIVE)
                                .reason().contains("Suspected defrost"));
                assertTrue(HumidityMonitor.withDefrostReason(decision, HumidityMonitor.DefrostState.UNKNOWN)
                                .reason().contains("Defrost status unknown"));
        }

        @Test
        void liveModesKeepSampledDefrostWarningsAndReportAwaitingEvaluationWhenDisabled() throws Exception {
                HumidityMonitor monitor = new HumidityMonitor("unused", "unused");
                long now = Instant.parse("2026-09-21T12:00:00Z").toEpochMilli();
                setMonitorField(monitor, "staticRpmMode", true);
                setMonitorField(monitor, "staticRpmSpeed", 2);
                HumidityMonitor.ControlDecision decision = HumidityMonitor.withDefrostReason(
                                new HumidityMonitor.ControlDecision("Static speed", 2, 2), HumidityMonitor.DefrostState.ACTIVE);
                monitor.recordSuccessfulTelemetry(decision, 50, 20, Double.NaN, Double.NaN, Instant.ofEpochMilli(now));
                assertTrue(monitor.liveJson(now).contains("Static speed + Suspected defrost"));
                setMonitorField(monitor, "staticRpmMode", false);
                assertTrue(monitor.liveJson(now).contains("Awaiting control evaluation"));
                setMonitorField(monitor, "manualOverrideActive", true);
                setMonitorField(monitor, "manualOverrideEndTime", now + 60_000);
                setMonitorField(monitor, "manualOverrideSpeed", 3);
                assertTrue(monitor.liveJson(now).contains("\"control_reason\":\"Manual override\""));
                assertTrue(monitor.liveJson(now).contains("\"policy_speed\":3, \"target_speed\":3"));
                setMonitorField(monitor, "monitorOnly", true);
                assertTrue(monitor.liveJson(now).contains("\"control_reason\":\"Monitor only\""));
                assertTrue(monitor.liveJson(now).contains("\"policy_speed\":null, \"target_speed\":null"));
                setMonitorField(monitor, "restartInProgress", new java.util.concurrent.atomic.AtomicBoolean(true));
                assertTrue(monitor.liveJson(now).contains("\"control_reason\":\"Maintenance restart\""));
        }

        @Test
        void realRecoveryDecisionHasStableReasonWithoutHumidityDelta() throws Exception {
                HumidityMonitor monitor = new HumidityMonitor("unused", "unused");
                setMonitorField(monitor, "boostActive", true);
                setMonitorField(monitor, "boostBaselineHumidity", 50.0);
                setMonitorField(monitor, "policyTargetSpeed", 2);
                setMonitorField(monitor, "commandedFanSpeed", 2);
                java.lang.reflect.Method update = HumidityMonitor.class.getDeclaredMethod("updateFanSpeed",
                                int.class, double.class, double.class, double.class, int.class, int.class,
                                boolean.class, int.class, int.class);
                update.setAccessible(true);
                for (int humidity : new int[] {52, 53}) {
                        HumidityMonitor.ControlDecision decision = (HumidityMonitor.ControlDecision) update.invoke(
                                        monitor, humidity, 18.0, 15.0, 21.0, 2, 5000, true, 0, 2);
                        assertEquals("Gentle humidity recovery", decision.reason());
                        assertEquals(2, decision.policySpeed());
                        assertEquals(2, decision.targetSpeed());
                }
                HumidityMonitor.ControlDecision boost = (HumidityMonitor.ControlDecision) update.invoke(
                                monitor, 60, 18.0, 15.0, 21.0, 2, 5000, true, 0, 2);
                assertEquals("Shower boost", boost.reason());
                assertEquals(3, boost.policySpeed());
                assertEquals(3, boost.targetSpeed());
                HumidityMonitor.ControlDecision veryHigh = (HumidityMonitor.ControlDecision) update.invoke(
                                monitor, 80, 18.0, 15.0, 21.0, 2, 5000, true, 0, 2);
                assertEquals("Very high humidity", veryHigh.reason());
                assertEquals(3, veryHigh.policySpeed());
        }

        private static void setMonitorField(HumidityMonitor monitor, String name, Object value) throws Exception {
                java.lang.reflect.Field field = HumidityMonitor.class.getDeclaredField(name);
                field.setAccessible(true);
                field.set(monitor, value);
        }

    private static final HumidityMonitor.HumidityPolicy DEFAULT_POLICY =
            new HumidityMonitor.HumidityPolicy(4, 1, 3, 1, 30, 65, 80);
    private static final int NO_HYSTERESIS = 0;
    // Matches the FAN_MIN_COMMAND_INTERVAL_SECONDS / FAN_RETRY_INTERVAL_SECONDS defaults.
    private static final HumidityMonitor.FanCommandPacing DEFAULT_PACING =
            new HumidityMonitor.FanCommandPacing(120_000L, 120_000L, 1_800_000L, 3);
    // Matches the HEAT_LOSS_* defaults, with the floor derived from NORMAL_SPEED.
    private static final HeatLossGuardPolicy.HeatLossGuardConfig DEFAULT_HEAT_LOSS =
            new HeatLossGuardPolicy.HeatLossGuardConfig(true, 23.0, 5.0, 600_000L, 0.15, 0.3, 80, 1);

    @Test
    void usesLowSpeedAtAndBelowLowThreshold() {
        assertEquals(1, HumidityMonitor.selectHumiditySpeed(29, 30, 65, 3, 3, NO_HYSTERESIS));
        assertEquals(1, HumidityMonitor.selectHumiditySpeed(30, 30, 65, 3, 3, NO_HYSTERESIS));
    }

    @Test
    void usesConfiguredNormalSpeedBetweenThresholds() {
        assertEquals(3, HumidityMonitor.selectHumiditySpeed(31, 30, 65, 3, 3, NO_HYSTERESIS));
        assertEquals(3, HumidityMonitor.selectHumiditySpeed(64, 30, 65, 3, 3, NO_HYSTERESIS));
    }

    @Test
    void usesHighSpeedAtAndAboveHighThreshold() {
        assertEquals(2, HumidityMonitor.selectHumiditySpeed(65, 30, 65, 1, 1, NO_HYSTERESIS));
        assertEquals(2, HumidityMonitor.selectHumiditySpeed(80, 30, 65, 1, 1, NO_HYSTERESIS));
    }

    @Test
    void highHumidityDoesNotLowerConfiguredNormalSpeed() {
        assertEquals(3, HumidityMonitor.selectHumiditySpeed(64, 30, 65, 3, 3, NO_HYSTERESIS));
        assertEquals(3, HumidityMonitor.selectHumiditySpeed(65, 30, 65, 3, 3, NO_HYSTERESIS));
    }

    @Test
    void highHumidityOverridesQuietNightWhenCoolingIsUnavailable() {
        assertEquals(2, HumidityMonitor.selectAutomaticSpeed(65, true, 0, 30, 65, 1, 1, NO_HYSTERESIS));
        assertEquals(2, HumidityMonitor.selectAutomaticSpeed(79, true, 0, 30, 65, 1, 1, NO_HYSTERESIS));
    }

    @Test
    void quietNightStillUsesSpeedOneAtNormalHumidity() {
        assertEquals(1, HumidityMonitor.selectAutomaticSpeed(49, true, 0, 30, 65, 2, 2, NO_HYSTERESIS));
    }

    @Test
    void coolingDoesNotLowerAHigherHumidityTarget() {
        assertEquals(3, HumidityMonitor.selectAutomaticSpeed(70, false, 2, 30, 65, 3, 3, NO_HYSTERESIS));
    }

    @Test
    void raisedSpeedIsHeldThroughTheHumidityDeadband() {
        // Reading 65 raises the fan to speed 2; jitter back to 63 must not drop it again.
        assertEquals(2, HumidityMonitor.selectAutomaticSpeed(65, true, 0, 30, 65, 1, 1, 3));
        assertEquals(2, HumidityMonitor.selectAutomaticSpeed(63, true, 0, 30, 65, 1, 2, 3));
        assertEquals(2, HumidityMonitor.selectAutomaticSpeed(62, true, 0, 30, 65, 1, 2, 3));
        // Only a genuine drop past the deadband returns to the quiet night speed.
        assertEquals(1, HumidityMonitor.selectAutomaticSpeed(61, true, 0, 30, 65, 1, 2, 3));
    }

    @Test
    void deadbandDoesNotDelayTheFirstRise() {
        assertEquals(1, HumidityMonitor.selectAutomaticSpeed(64, true, 0, 30, 65, 1, 1, 3));
        assertEquals(2, HumidityMonitor.selectAutomaticSpeed(65, true, 0, 30, 65, 1, 1, 3));
    }

    @Test
    void loweredSpeedIsHeldThroughTheDryDeadband() {
        assertEquals(1, HumidityMonitor.selectHumiditySpeed(30, 30, 65, 3, 3, 3));
        assertEquals(1, HumidityMonitor.selectHumiditySpeed(33, 30, 65, 3, 1, 3));
        assertEquals(3, HumidityMonitor.selectHumiditySpeed(34, 30, 65, 3, 1, 3));
    }

    @Test
    void nightSpeedIsLimitedWithoutALargeHumidityDelta() {
        assertEquals(2, HumidityMonitor.limitNightSpeed(3, true, 80,
                Double.NaN, false, DEFAULT_POLICY));
        assertEquals(2, HumidityMonitor.limitNightSpeed(3, true, 53,
                50.0, false, DEFAULT_POLICY));
    }

    @Test
    void largeHumidityDeltaCanExceedNightLimit() {
        assertEquals(3, HumidityMonitor.limitNightSpeed(3, true, 54,
                50.0, false, DEFAULT_POLICY));
        assertEquals(3, HumidityMonitor.limitNightSpeed(3, true, 50,
                50.0, true, DEFAULT_POLICY));
    }

    @Test
    void nightLimitDoesNotApplyDuringTheDay() {
        assertEquals(3, HumidityMonitor.limitNightSpeed(3, false, 50,
                Double.NaN, false, DEFAULT_POLICY));
    }

    @Test
    void aSettledSetpointIsNeverRewritten() {
        HumidityMonitor.FanCommandDecision decision = HumidityMonitor.decideFanCommand(
                false, false, true, 10_000_000L, 0, 2, DEFAULT_PACING);

        assertFalse(decision.send());
                assertEquals(2, decision.attempts());
    }

        @Test
        void briefAcceptanceDoesNotEraseRetryBackoff() {
                HumidityMonitor.FanCommandFeedback feedback = new HumidityMonitor.FanCommandFeedback(4, -1);
                feedback = HumidityMonitor.updateFanCommandFeedback(feedback, true, 30_000L, DEFAULT_PACING);
                assertEquals(4, feedback.attempts());
                feedback = HumidityMonitor.updateFanCommandFeedback(feedback, false, 180_000L, DEFAULT_PACING);
                assertEquals(-1, feedback.settledSince());

                HumidityMonitor.FanCommandDecision decision = HumidityMonitor.decideFanCommand(
                                false, false, false, 180_000L, 0L, feedback.attempts(), DEFAULT_PACING);
                assertFalse(decision.send());
                assertEquals(300_000L, decision.waitMillis());
                assertEquals(4, decision.attempts());
                assertTrue(HumidityMonitor.decideFanCommand(false, false, false, 480_000L, 0L,
                                feedback.attempts(), DEFAULT_PACING).send());
        }

        @Test
        void retryBackoffResetsOnlyAfterAnUninterruptedStableWindow() {
                HumidityMonitor.FanCommandFeedback feedback = new HumidityMonitor.FanCommandFeedback(4, -1);
                feedback = HumidityMonitor.updateFanCommandFeedback(feedback, true, 0L, DEFAULT_PACING);
                feedback = HumidityMonitor.updateFanCommandFeedback(feedback, true, 1_799_999L, DEFAULT_PACING);
                assertEquals(4, feedback.attempts());
                feedback = HumidityMonitor.updateFanCommandFeedback(feedback, false, 1_800_000L, DEFAULT_PACING);
                feedback = HumidityMonitor.updateFanCommandFeedback(feedback, true, 1_830_000L, DEFAULT_PACING);
                feedback = HumidityMonitor.updateFanCommandFeedback(feedback, true, 3_629_999L, DEFAULT_PACING);
                assertEquals(4, feedback.attempts());
                feedback = HumidityMonitor.updateFanCommandFeedback(feedback, true, 3_630_000L, DEFAULT_PACING);
                assertEquals(0, feedback.attempts());
        }

    @Test
    void raisingSpeedIsSentImmediatelyButLoweringWaitsOutTheMinimumSpacing() {
        assertTrue(HumidityMonitor.decideFanCommand(true, true, false, 1_000L, 0, 0, DEFAULT_PACING)
                .send());
        assertFalse(HumidityMonitor.decideFanCommand(true, false, false, 1_000L, 0, 0, DEFAULT_PACING)
                .send());
        assertTrue(HumidityMonitor.decideFanCommand(true, false, false, 120_000L, 0, 0, DEFAULT_PACING)
                .send());
    }

    @Test
    void aNewTargetClearsTheBackoffAttemptCount() {
        HumidityMonitor.FanCommandDecision decision = HumidityMonitor.decideFanCommand(
                true, true, false, 1_000L, 0, 7, DEFAULT_PACING);

        assertTrue(decision.send());
        assertEquals(0, decision.attempts());
    }

    @Test
    void aRejectedSetpointBacksOffInsteadOfCyclingTheFan() {
        // The unit keeps reverting our setpoint. The first attempts stay at the retry interval,
        // then the wait doubles so the fan settles instead of stepping every minute.
        assertEquals(120_000L, HumidityMonitor.retryIntervalMillis(0, DEFAULT_PACING));
        assertEquals(120_000L, HumidityMonitor.retryIntervalMillis(2, DEFAULT_PACING));
        assertEquals(240_000L, HumidityMonitor.retryIntervalMillis(3, DEFAULT_PACING));
        assertEquals(480_000L, HumidityMonitor.retryIntervalMillis(4, DEFAULT_PACING));
        assertEquals(1_800_000L, HumidityMonitor.retryIntervalMillis(30, DEFAULT_PACING));

        // At attempt 3 the next write is 4 minutes out, not on the next poll.
        assertFalse(HumidityMonitor.decideFanCommand(false, false, false, 239_999L, 0, 3,
                DEFAULT_PACING).send());
        HumidityMonitor.FanCommandDecision due = HumidityMonitor.decideFanCommand(
                false, false, false, 240_000L, 0, 3, DEFAULT_PACING);
        assertTrue(due.send());
        assertEquals(4, due.attempts());
    }

    @Test
    void setpointReadbackDecidesWhetherTheUnitHeldOurCommand() {
        assertTrue(HumidityMonitor.setpointHeld(2, 1, 2));
        assertFalse(HumidityMonitor.setpointHeld(2, 2, 1));
        // Without a read-back the duty-derived estimate is all we have.
        assertTrue(HumidityMonitor.setpointHeld(2, 2, -1));
        assertFalse(HumidityMonitor.setpointHeld(2, 1, -1));
        assertFalse(HumidityMonitor.setpointHeld(-1, -1, -1));
    }

    @Test
    void dutyMapsToTheNearestDocumentedSpeed() {
        assertEquals(0, HumidityMonitor.estimateFanSpeed(0, 3000));
        assertEquals(1, HumidityMonitor.estimateFanSpeed(1064, 3000));
        assertEquals(2, HumidityMonitor.estimateFanSpeed(2000, 5000));
        // The old hard bounds classified 60% as speed 3, leaving a permanent phantom mismatch.
        assertEquals(2, HumidityMonitor.estimateFanSpeed(2400, 6000));
        assertEquals(3, HumidityMonitor.estimateFanSpeed(3124, 7000));
        assertEquals(4, HumidityMonitor.estimateFanSpeed(3600, 10000));
    }

        @Test
        void zeroReadbackDoesNotRetryAFanAlreadyRunningAtTheRequestedSpeed() {
                for (int speed : new int[] {2, 3}) {
                        assertEquals(-1, HumidityMonitor.effectiveFanSetpoint(0, speed));
                        boolean held = HumidityMonitor.setpointHeld(speed, speed, 0);
                        assertTrue(held);
                        assertFalse(HumidityMonitor.decideFanCommand(false, false, held, 1_800_000L, 0L,
                                        6, DEFAULT_PACING).send());
                }
        }

        @Test
        void zeroReadbackStillDetectsRealSpeedMismatchesAndStoppedFans() {
                assertFalse(HumidityMonitor.setpointHeld(3, 2, 0));
                assertFalse(HumidityMonitor.setpointHeld(2, 0, 0));
                assertFalse(HumidityMonitor.setpointHeld(0, 3, 0));
                assertTrue(HumidityMonitor.setpointHeld(0, 0, 0));
                assertEquals(0, HumidityMonitor.effectiveFanSetpoint(0, 0));
                assertEquals(2, HumidityMonitor.effectiveFanSetpoint(2, 3));
                assertEquals(-1, HumidityMonitor.effectiveFanSetpoint(8, 3));
        }

    @Test
        void showerBoostStepsDownBeforeHumidityFullyRecovers() {
                assertEquals(3, HumidityMonitor.selectHumidityRecoverySpeed(60, 50.0, DEFAULT_POLICY, 0, 3,
                NO_HYSTERESIS));
                assertEquals(2, HumidityMonitor.selectHumidityRecoverySpeed(53, 50.0, DEFAULT_POLICY, 0, 3,
                NO_HYSTERESIS));
    }

    @Test
    void configuredDeltaTriggersAtTheBoundary() {
        assertFalse(HumidityMonitor.hasHumidityRise(48, 45.0, DEFAULT_POLICY));
        assertTrue(HumidityMonitor.hasHumidityRise(49, 45.0, DEFAULT_POLICY));
    }

    @Test
    void showerBoostNeverUndercutsConfiguredOrAbsoluteProtection() {
                assertEquals(3, HumidityMonitor.selectHumidityRecoverySpeed(65, 55.0, DEFAULT_POLICY, 0, 3,
                NO_HYSTERESIS));
        HumidityMonitor.HumidityPolicy limitedBoost =
            new HumidityMonitor.HumidityPolicy(4, 1, 2, 1, 30, 65, 80);
        assertEquals(3, HumidityMonitor.selectHumidityRecoverySpeed(80, 79.0, limitedBoost, 0, 2,
                NO_HYSTERESIS));
    }

    @Test
    void coldWeatherGuardCannotReboostAModestEventAboveSpeedTwo() {
        HeatLossGuardPolicy.HeatLossState guard = HeatLossGuardPolicy.HeatLossState.IDLE;
        int policyTarget = 1;
        for (int poll = 0; poll <= 80; poll++) {
            int humidity = poll < 20 ? 59 : poll < 60 ? 56 : 61;
            policyTarget = HumidityMonitor.selectHumidityRecoverySpeed(humidity, 54.6,
                    DEFAULT_POLICY, 0, policyTarget, 3);
            guard = HeatLossGuardPolicy.evaluate(guard, policyTarget, humidity,
                    HumidityPhysics.mixingRatioGramsPerKg(humidity, 20.4), 20.4, 10.1,
                    1_000L + poll * 30_000L, DEFAULT_HEAT_LOSS);
            int speed = HeatLossGuardPolicy.guardedSpeed(policyTarget, 0, guard, DEFAULT_HEAT_LOSS);
            assertEquals(2, policyTarget);
            assertTrue(speed >= 1 && speed <= 2, "Speed at poll " + poll);
            if (poll == 0 || poll == 40 || poll == 60) {
                assertEquals(2, speed);
            }
        }
        policyTarget = HumidityMonitor.selectHumidityRecoverySpeed(79, 54.6,
                DEFAULT_POLICY, 0, policyTarget, 3);
        guard = HeatLossGuardPolicy.evaluate(guard, policyTarget, 79,
                HumidityPhysics.mixingRatioGramsPerKg(79, 20.4), 20.4, 10.1,
                2_431_000L, DEFAULT_HEAT_LOSS);
        assertEquals(3, HeatLossGuardPolicy.guardedSpeed(policyTarget, 0, guard, DEFAULT_HEAT_LOSS));
    }

    @Test
    void gentleRecoveryHonoursConfiguredSpeedsAndMissingBaselineIsConservative() {
        for (int boostSpeed : new int[] {1, 2, 3, 4}) {
            HumidityMonitor.HumidityPolicy policy =
                    new HumidityMonitor.HumidityPolicy(4, 1, boostSpeed, 1, 30, 65, 80);
            assertEquals(Math.min(2, boostSpeed), HumidityMonitor.selectHumidityRecoverySpeed(
                    59, 54.6, policy, 0, 1, 3));
            assertEquals(Math.max(2, boostSpeed), HumidityMonitor.selectHumidityRecoverySpeed(
                    70, 54.6, policy, 0, 1, 3));
        }
        HumidityMonitor.HumidityPolicy highNormal =
                new HumidityMonitor.HumidityPolicy(4, 1, 3, 4, 30, 65, 80);
        assertEquals(4, HumidityMonitor.selectHumidityRecoverySpeed(59, 54.6, highNormal, 0, 4, 3));
        assertEquals(3, HumidityMonitor.selectHumidityRecoverySpeed(59, Double.NaN,
                DEFAULT_POLICY, 0, 1, 3));
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
    void showerBoostStopsOnlyAtItsFrozenPreRiseBaseline() {
        assertFalse(HumidityMonitor.shouldDeactivateBoost(46, 45.0, Double.NaN, Double.NaN));
        assertTrue(HumidityMonitor.shouldDeactivateBoost(45, 45.0, Double.NaN, Double.NaN));
        assertTrue(HumidityMonitor.shouldDeactivateBoost(44, 45.0, Double.NaN, Double.NaN));
        assertEquals(46.0, HumidityMonitor.humidityRecoveryTarget(46.0));
    }

    @Test
    void recoveryNeverLowersAnActiveCoolingTarget() {
                assertEquals(3, HumidityMonitor.selectHumidityRecoverySpeed(47, 45.0, DEFAULT_POLICY, 3, 3,
                NO_HYSTERESIS));
                assertEquals(2, HumidityMonitor.selectHumidityRecoverySpeed(49, 45.0,
            new HumidityMonitor.HumidityPolicy(4, 1, 2, 1, 30, 65, 80), 2, 2, NO_HYSTERESIS));
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

        assertEquals(new HumidityMonitor.ControlState(true, 45.0, 0),
                HumidityMonitor.restorableControlState(true, active));
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
            statement.execute("CREATE TABLE control_state (id INTEGER PRIMARY KEY, "
                    + "boost_active INTEGER NOT NULL DEFAULT 0, boost_baseline REAL, "
                    + "boost_end INTEGER NOT NULL DEFAULT 0)");
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

    @Test
    void rejectsUnsafeFanPacingConfiguration() {
        HumidityMonitor.validateFanPacingConfiguration(DEFAULT_PACING, 3, 30, 65);
        assertThrows(IllegalArgumentException.class, () ->
                HumidityMonitor.validateFanPacingConfiguration(
                        new HumidityMonitor.FanCommandPacing(0, 120_000L, 1_800_000L, 3), 3, 30, 65));
        assertThrows(IllegalArgumentException.class, () ->
                HumidityMonitor.validateFanPacingConfiguration(
                        new HumidityMonitor.FanCommandPacing(120_000L, 0, 1_800_000L, 3), 3, 30, 65));
        assertThrows(IllegalArgumentException.class, () ->
                HumidityMonitor.validateFanPacingConfiguration(
                        new HumidityMonitor.FanCommandPacing(120_000L, 120_000L, 60_000L, 3), 3, 30, 65));
        assertThrows(IllegalArgumentException.class, () ->
                HumidityMonitor.validateFanPacingConfiguration(
                        new HumidityMonitor.FanCommandPacing(120_000L, 120_000L, 1_800_000L, 0), 3, 30, 65));
        assertThrows(IllegalArgumentException.class, () ->
                HumidityMonitor.validateFanPacingConfiguration(DEFAULT_PACING, -1, 30, 65));
        assertThrows(IllegalArgumentException.class, () ->
                HumidityMonitor.validateFanPacingConfiguration(DEFAULT_PACING, 35, 30, 65));
    }

    @Test
    void rejectsUnsafeHeatLossConfiguration() {
        HumidityMonitor.validateHeatLossConfiguration(DEFAULT_HEAT_LOSS);
        assertThrows(IllegalArgumentException.class, () -> HumidityMonitor.validateHeatLossConfiguration(
                heatLossConfig(Double.NaN, 5.0, 600_000L, 0.15, 0.3, 80, 1)));
        assertThrows(IllegalArgumentException.class, () -> HumidityMonitor.validateHeatLossConfiguration(
                heatLossConfig(23.0, 0.0, 600_000L, 0.15, 0.3, 80, 1)));
        assertThrows(IllegalArgumentException.class, () -> HumidityMonitor.validateHeatLossConfiguration(
                heatLossConfig(23.0, 5.0, 0L, 0.15, 0.3, 80, 1)));
        assertThrows(IllegalArgumentException.class, () -> HumidityMonitor.validateHeatLossConfiguration(
                heatLossConfig(23.0, 5.0, 600_000L, 0.0, 0.3, 80, 1)));
        assertThrows(IllegalArgumentException.class, () -> HumidityMonitor.validateHeatLossConfiguration(
                heatLossConfig(23.0, 5.0, 600_000L, 0.15, -0.1, 80, 1)));
        assertThrows(IllegalArgumentException.class, () -> HumidityMonitor.validateHeatLossConfiguration(
                heatLossConfig(23.0, 5.0, 600_000L, 0.15, 0.3, 101, 1)));
        assertThrows(IllegalArgumentException.class, () -> HumidityMonitor.validateHeatLossConfiguration(
                heatLossConfig(23.0, 5.0, 600_000L, 0.15, 0.3, 80, 0)));
        assertThrows(IllegalArgumentException.class, () -> HumidityMonitor.validateHeatLossConfiguration(
                heatLossConfig(23.0, 5.0, 600_000L, 0.15, 0.3, 80, 5)));
    }

    @Test
    void persistedBoostBaselineStaysARelativeHumidityForRollback() throws Exception {
        // A 1.72 or 1.73 binary reading this row compares it directly against the humidity percentage. If
        // this value were ever written as a mixing ratio, that comparison could never be true again and the
        // older binary would hold the fan at boost speed indefinitely.
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            HumidityMonitor.ensureControlStateTable(connection);
            HumidityMonitor.saveControlState(connection,
                    new HumidityMonitor.ControlState(true, 45.0, 0L));

            double persisted = HumidityMonitor.loadControlState(connection).boostBaseline();

            assertEquals(45.0, persisted);
            assertTrue(HumidityMonitor.shouldDeactivateBoost(45, persisted, Double.NaN, Double.NaN));
            assertFalse(HumidityMonitor.shouldDeactivateBoost(46, persisted, Double.NaN, Double.NaN));
        }
    }

    @Test
    void moistureBaselineConvertsEachReadingBeforeAveraging() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE humidity_readings (timestamp DATETIME, humidity INTEGER, "
                    + "temp_extract REAL)");
            statement.execute("INSERT INTO humidity_readings VALUES "
                    + "('2026-08-14 09:35:00', 55, 20.0), ('2026-08-14 09:45:00', 55, 10.0), "
                    + "('2026-08-14 09:00:00', 90, 20.0), ('2026-08-14 09:50:00', NULL, 20.0), "
                    + "('2026-08-14 09:55:00', 55, NULL)");

            double average = HumidityMonitor.historicalMoistureAverage(connection,
                    Instant.parse("2026-08-14T10:00:00Z"), 30);

            double perReading = (HumidityPhysics.mixingRatioGramsPerKg(55, 20.0)
                    + HumidityPhysics.mixingRatioGramsPerKg(55, 10.0)) / 2.0;
            assertEquals(perReading, average, 1e-9);
            // Averaging the humidity column first and converting once would land somewhere else entirely,
            // which is the whole reason this cannot be done in SQL.
            assertTrue(Math.abs(average - HumidityPhysics.mixingRatioGramsPerKg(55, 15.0)) > 0.2);
        }
    }

    @Test
    void moistureBaselineIsUnavailableWhenNoReadingCanBeConverted() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE humidity_readings (timestamp DATETIME, humidity INTEGER, "
                    + "temp_extract REAL)");
            statement.execute("INSERT INTO humidity_readings VALUES "
                    + "('2026-08-14 09:45:00', NULL, 20.0), ('2026-08-14 09:50:00', 55, NULL)");

            assertTrue(Double.isNaN(HumidityMonitor.historicalMoistureAverage(connection,
                    Instant.parse("2026-08-14T10:00:00Z"), 30)));
        }
    }

    private static HeatLossGuardPolicy.HeatLossGuardConfig heatLossConfig(double indoorCeilingC,
            double tempDeltaC, long probeWindowMillis, double progressGramsPerKg,
            double peakMarginGramsPerKg, int overrideHumidityPct, int floorSpeed) {
        return new HeatLossGuardPolicy.HeatLossGuardConfig(true, indoorCeilingC, tempDeltaC,
                probeWindowMillis, progressGramsPerKg, peakMarginGramsPerKg, overrideHumidityPct, floorSpeed);
    }
}
