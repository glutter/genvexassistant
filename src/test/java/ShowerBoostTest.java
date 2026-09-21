import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShowerBoostTest {
    private static final double NO_MOISTURE = Double.NaN;
    private static final HumidityMonitor.HumidityPolicy POLICY =
            new HumidityMonitor.HumidityPolicy(4, 1, 3, 1, 30, 65, 80);

    private static HumidityMonitor.HumidityRiseDetector detector() {
        return new HumidityMonitor.HumidityRiseDetector(300_000L, 75_000L);
    }

    @Test
    void smallEveningRiseUsesSpeedTwoInsteadOfShowerSpeed() {
        assertEquals(2, HumidityMonitor.selectHumidityRecoverySpeed(59, 54.6, POLICY, 0, 1, 3));
        assertEquals(2, HumidityMonitor.selectHumidityRecoverySpeed(61, 54.6, POLICY, 0, 2, 3));
    }

    @Test
    void clearShowerEscalatesImmediatelyAndStepsDownWithHysteresis() {
        assertEquals(3, HumidityMonitor.selectHumidityRecoverySpeed(79, 54.6, POLICY, 0, 2, 3));
        assertEquals(3, HumidityMonitor.selectHumidityRecoverySpeed(63, 55.0, POLICY, 0, 2, 3));
        assertEquals(3, HumidityMonitor.selectHumidityRecoverySpeed(60, 55.0, POLICY, 0, 3, 3));
        assertEquals(2, HumidityMonitor.selectHumidityRecoverySpeed(59, 55.0, POLICY, 0, 3, 3));
        assertEquals(2, HumidityMonitor.selectHumidityRecoverySpeed(60, 55.0, POLICY, 0, 2, 3));
        assertEquals(2, HumidityMonitor.selectHumidityRecoverySpeed(62, 55.0, POLICY, 0, 2, 3));
    }

    @Test
    void gentleRecoveryContinuesWhileDryingWithoutReturningToSpeedThree() {
        HumidityMonitor.BoostRecoveryProgress progress = progress();
        int speed = 1;
        for (int poll = 0; poll <= 120; poll++) {
            int humidity = 61 - poll / 30;
            speed = HumidityMonitor.selectHumidityRecoverySpeed(humidity, 54.6, POLICY, 0, speed, 3);
            assertEquals(2, speed);
            assertFalse(progress.update(humidity,
                    HumidityPhysics.mixingRatioGramsPerKg(humidity, 20.5), false, speed >= 2,
                    poll * 30_000L));
        }
    }

    @Test
    void gentleRecoveryStallIsObservedAtSpeedTwo() {
        HumidityMonitor.BoostRecoveryProgress progress = progress();
        int speed = 1;
        for (int poll = 0; poll <= 60; poll++) {
            speed = HumidityMonitor.selectHumidityRecoverySpeed(59, 54.6, POLICY, 0, speed, 3);
            assertEquals(2, speed);
            assertEquals(poll == 60, progress.update(59, 8.9, false, speed >= 2, poll * 30_000L));
        }
    }

    @Test
    void slowWeatherRiseNeverTriggersEvenAboveTheLongTermBaseline() {
        HumidityMonitor.HumidityRiseDetector detector = detector();
        for (int poll = 0; poll <= 160; poll++) {
            int humidity = 60 + poll / 20;
            assertFalse(detector.update(humidity, 56.0, poll * 30_000L, POLICY),
                    "Slow weather rise triggered at poll " + poll);
        }
    }

    @Test
    void fourPointRiseWithinFiveMinutesTriggersAtTheBoundary() {
        HumidityMonitor.HumidityRiseDetector detector = detector();
        for (int poll = 0; poll < 10; poll++) {
            assertFalse(detector.update(60 + poll * 4 / 10, 60.0, poll * 30_000L, POLICY));
        }
        assertTrue(detector.update(64, 60.0, 300_000L, POLICY));
    }

    @Test
    void suddenShowerTriggersWithoutWaitingForAFullWindow() {
        HumidityMonitor.HumidityRiseDetector detector = detector();
        assertFalse(detector.update(57, 57.0, 0L, POLICY));
        assertTrue(detector.update(79, 57.0, 30_000L, POLICY));
    }

    @Test
    void expiredLowReadingCannotTurnASlowRiseIntoAShower() {
        HumidityMonitor.HumidityRiseDetector detector = detector();
        assertFalse(detector.update(60, 56.0, 0L, POLICY));
        for (int poll = 1; poll <= 10; poll++) {
            assertFalse(detector.update(61, 56.0, poll * 30_000L, POLICY));
        }
        assertFalse(detector.update(64, 56.0, 330_000L, POLICY));
    }

    @Test
    void aQuickReboundBelowTheLongTermBaselineDoesNotTrigger() {
        HumidityMonitor.HumidityRiseDetector detector = detector();
        assertFalse(detector.update(50, 60.0, 0L, POLICY));
        assertFalse(detector.update(60, 60.0, 30_000L, POLICY));
    }

    @Test
    void startupAndGapsRequireNewEvidenceOfARapidRise() {
        HumidityMonitor.HumidityRiseDetector detector = detector();
        assertFalse(detector.update(60, 50.0, 0L, POLICY));
        assertFalse(detector.update(70, 50.0, 75_001L, POLICY));
        assertTrue(detector.update(74, 50.0, 105_001L, POLICY));
    }

    @Test
    void recoveryResetCannotReuseThePreviousShowersLowReading() {
        HumidityMonitor.HumidityRiseDetector detector = detector();
        assertFalse(detector.update(60, 60.0, 0L, POLICY));
        assertTrue(detector.update(80, 60.0, 30_000L, POLICY));
        detector.reset();
        assertFalse(detector.update(64, 60.0, 60_000L, POLICY));
        assertFalse(detector.update(65, 60.0, 90_000L, POLICY));
        assertTrue(detector.update(69, 64.0, 120_000L, POLICY));
    }

    @Test
    void invalidHumidityAndBackwardsTimeDiscardStaleEvidence() {
        HumidityMonitor.HumidityRiseDetector detector = detector();
        assertFalse(detector.update(60, 60.0, 30_000L, POLICY));
        assertFalse(detector.update(-1, 60.0, 60_000L, POLICY));
        assertFalse(detector.update(70, 60.0, 90_000L, POLICY));
        assertFalse(detector.update(80, 60.0, 60_000L, POLICY));
    }

    @Test
    void humidityAboveBaselineDoesNotEndShowerBoost() {
        assertFalse(HumidityMonitor.shouldDeactivateBoost(46, 45.0, NO_MOISTURE, NO_MOISTURE));
    }

    private static HumidityMonitor.BoostRecoveryProgress progress() {
        return new HumidityMonitor.BoostRecoveryProgress(1_800_000L, 75_000L);
    }

    @Test
    void restoredBoostWithSlowWeatherRiseReleasesAfterAFullObservationWindow() {
        HumidityMonitor.ControlState restored = HumidityMonitor.restorableControlState(true,
                new HumidityMonitor.ControlState(true, 52.0, 0L));
        HumidityMonitor.BoostRecoveryProgress progress = progress();
        HumidityMonitor.HumidityRiseDetector detector = detector();
        boolean active = restored.boostActive();
        for (int poll = 0; poll <= 120; poll++) {
            int humidity = 65 + Math.min(3, poll / 30);
            double moisture = HumidityPhysics.mixingRatioGramsPerKg(humidity, 22.0);
            boolean rapidRise = detector.update(humidity, 64.0, poll * 30_000L, POLICY);
            assertFalse(rapidRise);
            if (active && (HumidityMonitor.shouldDeactivateBoost(humidity, restored.boostBaseline(),
                    moisture, Double.NaN) || progress.update(humidity, moisture, rapidRise, true,
                    poll * 30_000L))) {
                active = false;
                detector.reset();
            }
            assertEquals(poll < 60, active, "Recovery state at poll " + poll);
        }
        assertEquals(2, HumidityMonitor.selectAutomaticSpeed(68, false, 0, 30, 65, 1, 3, 3));
        assertEquals(3, HumidityMonitor.selectHumidityRecoverySpeed(80, restored.boostBaseline(), POLICY, 0, 3, 3));
    }

    @Test
    void measurableDryingKeepsBoostAcrossMultipleWindows() {
        HumidityMonitor.BoostRecoveryProgress progress = progress();
        for (int poll = 0; poll <= 180; poll++) {
            assertFalse(progress.update(70 - poll / 20, 12.0 - poll * 0.01, false, true,
                    poll * 30_000L));
        }
    }

    @Test
    void recoveryThatInitiallyDriesButLaterStallsAlsoReleases() {
        HumidityMonitor.BoostRecoveryProgress progress = progress();
        for (int poll = 0; poll < 120; poll++) {
            double moisture = 12.0 - Math.min(60, poll) * 0.01;
            assertFalse(progress.update(70, moisture, false, true, poll * 30_000L));
        }
        assertTrue(progress.update(70, 11.4, false, true, 3_600_000L));
    }

    @Test
    void newShowerNearTheDeadlineGetsAFreshProgressWindow() {
        HumidityMonitor.BoostRecoveryProgress progress = progress();
        for (int poll = 0; poll < 120; poll++) {
            assertFalse(progress.update(poll < 60 ? 65 : 75, poll < 60 ? 10.0 : 12.0,
                    poll == 60, true, poll * 30_000L));
        }
        assertTrue(progress.update(75, 12.0, false, true, 3_600_000L));
    }

    @Test
    void coolingAirIsJudgedByMoistureNotRisingRelativeHumidity() {
        HumidityMonitor.BoostRecoveryProgress progress = progress();
        for (int poll = 0; poll <= 60; poll++) {
            int humidity = 65 + poll / 30;
            double temperature = 22.0 - poll * 0.025;
            assertFalse(progress.update(humidity,
                    HumidityPhysics.mixingRatioGramsPerKg(humidity, temperature), false, true,
                    poll * 30_000L));
        }
    }

    @Test
    void missingTemperatureUsesTwoHumidityPointsAndRejectsSinglePointJitter() {
        for (int fall : new int[] {1, 2}) {
            HumidityMonitor.BoostRecoveryProgress progress = progress();
            for (int poll = 0; poll < 60; poll++) {
                assertFalse(progress.update(65, NO_MOISTURE, false, true, poll * 30_000L));
            }
            assertEquals(fall < 2, progress.update(65 - fall, NO_MOISTURE, false, true, 1_800_000L));
        }
    }

    @Test
    void gapsSuspensionAndMetricChangesCannotCountAsACompleteWindow() {
        for (int interruption = 0; interruption < 5; interruption++) {
            HumidityMonitor.BoostRecoveryProgress progress = progress();
            for (int poll = 0; poll < 30; poll++) {
                assertFalse(progress.update(65, 10.0, false, true, poll * 30_000L));
            }
            switch (interruption) {
                case 0 -> assertFalse(progress.update(65, 10.0, false, false, 900_000L));
                case 1 -> assertFalse(progress.update(-1, 10.0, false, true, 900_000L));
                case 2 -> progress.reset();
            }
            long restart = interruption == 3 ? 1_000_000L : 900_000L;
            double moisture = interruption == 4 ? NO_MOISTURE : 10.0;
            for (int poll = 0; poll < 60; poll++) {
                assertFalse(progress.update(65, moisture, false, true, restart + poll * 30_000L),
                        "Interruption " + interruption + " at poll " + poll);
            }
            assertTrue(progress.update(65, moisture, false, true, restart + 1_800_000L));
        }
    }

    @Test
    void progressStartsOnlyOnceBoostAirflowIsActuallyObserved() {
        HumidityMonitor.BoostRecoveryProgress progress = progress();
        for (int poll = 0; poll < 80; poll++) {
            assertFalse(progress.update(65, 10.0, false, poll >= 20, poll * 30_000L));
        }
        assertTrue(progress.update(65, 10.0, false, true, 2_400_000L));
    }

    @Test
    void backwardsClockStartsAFreshProgressWindow() {
        HumidityMonitor.BoostRecoveryProgress progress = progress();
        for (int poll = 0; poll < 60; poll++) {
            assertFalse(progress.update(65, 10.0, false, true, 3_600_000L + poll * 30_000L));
        }
        for (int poll = 0; poll < 60; poll++) {
            assertFalse(progress.update(65, 10.0, false, true, 3_600_000L + poll * 30_000L));
        }
        assertTrue(progress.update(65, 10.0, false, true, 5_400_000L));
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
