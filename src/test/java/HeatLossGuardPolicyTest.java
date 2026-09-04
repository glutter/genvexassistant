import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HeatLossGuardPolicyTest {
    /** Matches the HEAT_LOSS_* defaults, with the floor derived from NORMAL_SPEED. */
    private static final HeatLossGuardPolicy.HeatLossGuardConfig CONFIG =
            new HeatLossGuardPolicy.HeatLossGuardConfig(true, 23.0, 5.0, 600_000L, 0.15, 0.3, 80, 1);
    private static final HeatLossGuardPolicy.HeatLossState IDLE =
            HeatLossGuardPolicy.HeatLossState.IDLE;

    private static final long PROBE = 600_000L;
    private static final long POLL = 30_000L;
    /** Any positive instant; NOT_PROBING is 0, so the state machine's clock must never be zero. */
    private static final long T0 = 1_700_000_000_000L;

    // The readings at 03:01 that motivated the feature: 69 % humidity, a 6.0 C delta, no event in sight.
    private static final double INDOOR_C = 20.6;
    private static final double OUTSIDE_C = 14.6;
    private static final double STEADY_69 = HumidityPhysics.mixingRatioGramsPerKg(69, INDOOR_C);

    // Defaults of HUMIDITY_LOW/HIGH/VERY_HIGH_THRESHOLD, HUMIDITY_HYSTERESIS and NORMAL_SPEED.
    private static final int LOW = 30;
    private static final int HIGH = 65;
    private static final int VERY_HIGH = 80;
    private static final int HYSTERESIS = 3;
    private static final int NORMAL_SPEED = 1;

    @Test
    void armsOnlyWhenVentilationIsActuallyCostingHeat() {
        assertTrue(HeatLossGuardPolicy.isArmed(INDOOR_C, OUTSIDE_C, CONFIG), "the observed 03:01 readings");

        assertFalse(HeatLossGuardPolicy.isArmed(24.0, 14.0, CONFIG), "summer indoors needs no heat kept");
        assertFalse(HeatLossGuardPolicy.isArmed(23.0, 5.0, CONFIG), "the ceiling is exclusive");
        assertFalse(HeatLossGuardPolicy.isArmed(22.0, 19.0, CONFIG), "a 3 C delta is not worth guarding");
        assertFalse(HeatLossGuardPolicy.isArmed(20.0, 15.0, CONFIG), "the delta must be exceeded, not met");
        assertFalse(HeatLossGuardPolicy.isArmed(Double.NaN, 5.0, CONFIG), "a failed extract read");
        assertFalse(HeatLossGuardPolicy.isArmed(20.0, Double.NaN, CONFIG), "a failed outside read");
        assertFalse(HeatLossGuardPolicy.isArmed(20.0, -273.0, CONFIG), "an implausible outside read");
        assertFalse(HeatLossGuardPolicy.isArmed(INDOOR_C, OUTSIDE_C, disabled()), "the master switch");
    }

    @Test
    void theFirstArmedPollGivesTheFullTargetAndSeedsThePeak() {
        HeatLossGuardPolicy.HeatLossState state = step(IDLE, 3, 78, 11.80, T0);

        assertEquals(0, state.stepDown(), "nothing is taken away before anything has been measured");
        assertFalse(state.probing());
        assertEquals(11.80, state.peakMoisture());
        assertEquals(T0, state.peakTime());
        assertEquals(3, HeatLossGuardPolicy.guardedSpeed(3, 0, state, CONFIG));
    }

    @Test
    void aRunningShowerKeepsTheFullTargetWhileMoistureSitsAtItsPeak() {
        HeatLossGuardPolicy.HeatLossState state = step(IDLE, 3, 78, 11.80, T0);

        for (int poll = 1; poll * POLL < PROBE; poll++) {
            state = step(state, 3, 78, 11.80 - 0.01 * poll, T0 + poll * POLL);
            assertEquals(0, state.stepDown(), "still within the peak margin at poll " + poll);
            assertEquals(3, HeatLossGuardPolicy.guardedSpeed(3, 0, state, CONFIG));
        }
    }

    @Test
    void theFirstStepDownWaitsUntilMoistureFallsPastThePeakMargin() {
        HeatLossGuardPolicy.HeatLossState state = step(IDLE, 3, 78, 11.80, T0);

        state = step(state, 3, 78, 11.60, T0 + POLL);
        assertEquals(0, state.stepDown(), "0.20 g/kg off the peak is inside the 0.3 margin");

        state = step(state, 3, 77, 11.45, T0 + 2 * POLL);
        assertEquals(1, state.stepDown(), "0.35 g/kg off the peak means the event has passed its top");
        assertTrue(state.probing());
        assertEquals(11.45, state.referenceMoisture());
        assertEquals(2, HeatLossGuardPolicy.guardedSpeed(3, 0, state, CONFIG));
    }

    @Test
    void oneCountOfSensorFlickerCannotCostASpeedWithoutAScoredProbe() {
        // At 20.6 C an integer humidity count is 0.154 g/kg, so the 0.3 g/kg peak margin is 1.95 counts wide
        // and its boundary always lands between the peak reading and the one two below it. A single count of
        // flicker therefore crosses the margin in both directions. Cancelling the in-flight probe on the way
        // back in used to hand transition 7 a fresh step-down on the way back out: two speeds gone in 90
        // seconds with no window ever scored, and then the floor for the rest of the event.
        HeatLossGuardPolicy.HeatLossState state = step(IDLE, 3, 72, moistureAt(72), T0);
        assertEquals(0, state.stepDown(), "the peak itself gets the full target");

        state = step(state, 3, 70, moistureAt(70), T0 + POLL);
        assertEquals(1, state.stepDown(), "clear of the margin, so one speed is taken and observed");
        long probeStart = state.probeStartTime();
        assertTrue(state.probing());

        state = step(state, 3, 71, moistureAt(71), T0 + 2 * POLL);
        assertEquals(1, state.stepDown(), "back inside the margin holds the step");
        assertEquals(probeStart, state.probeStartTime(), "and the probe survives the flicker");

        state = step(state, 3, 70, moistureAt(70), T0 + 3 * POLL);
        assertEquals(1, state.stepDown(), "no second speed is taken without a scored window");
        assertEquals(probeStart, state.probeStartTime(), "the original probe is still the one running");
        assertEquals(2, HeatLossGuardPolicy.guardedSpeed(3, 0, state, CONFIG));

        // The window it kept is scored on its own deadline, and a plateau scores as the stall it is.
        state = step(state, 3, 70, moistureAt(70), T0 + POLL + PROBE);
        assertEquals(0, state.stepDown(), "flat moisture over the whole window gives the speed back");
        assertTrue(state.holding(), "and holds there until the air dries unaided");
        assertEquals(3, HeatLossGuardPolicy.guardedSpeed(3, 0, state, CONFIG));
    }

    @Test
    void aSecondShowerGetsTheFullTargetBackImmediately() {
        HeatLossGuardPolicy.HeatLossState state = step(IDLE, 3, 78, 11.80, T0);
        state = step(state, 3, 77, 11.45, T0 + POLL);
        assertEquals(1, state.stepDown(), "the guard had started to descend");

        state = step(state, 3, 79, 11.90, T0 + 2 * POLL);

        assertEquals(0, state.stepDown(), "a new peak is the next person's shower");
        assertFalse(state.probing());
        assertFalse(state.holding());
        assertEquals(11.90, state.peakMoisture());
        assertEquals(T0 + 2 * POLL, state.peakTime(), "and the new peak is fresh again");
        assertEquals(3, HeatLossGuardPolicy.guardedSpeed(3, 0, state, CONFIG));
    }

    @Test
    void aPolicyAskingForMoreAirIsTreatedAsAFreshEvent() {
        HeatLossGuardPolicy.HeatLossState state = firstProbe(10.43, 10.43, 2);
        assertEquals(1, state.stepDown());

        // Humidity crossed into the very-high band, or evening cooling escalated: the target went up, so a
        // stale peak must not be allowed to keep the fan below what the policy is now asking for.
        state = step(state, 3, 79, 10.43, T0 + PROBE + POLL);

        assertEquals(0, state.stepDown());
        assertEquals(3, HeatLossGuardPolicy.guardedSpeed(3, 0, state, CONFIG));
    }

    @Test
    void aProbeIsOnlyScoredOnceItsWindowHasElapsed() {
        HeatLossGuardPolicy.HeatLossState probing = firstProbe(10.43, 10.43, 3);

        for (long elapsed = POLL; elapsed < PROBE; elapsed += POLL) {
            // Moisture is flat, i.e. the probe is failing - but it is not yet the probe's turn to be judged.
            HeatLossGuardPolicy.HeatLossState state = step(probing, 3, 69, 10.43, T0 + PROBE + elapsed);
            assertEquals(1, state.stepDown(), "judged early after " + elapsed + " ms");
            assertTrue(state.probing());
            assertFalse(state.holding());
        }
    }

    @Test
    void aProbeThatKeepsDryingKeepsItsStepAndEarnsAnother() {
        HeatLossGuardPolicy.HeatLossState state = firstProbe(10.43, 10.43, 3);

        state = step(state, 3, 68, 10.20, T0 + 2 * PROBE);
        assertEquals(1, state.stepDown(), "0.23 g/kg in ten minutes is still drying");
        assertFalse(state.probing(), "so the next poll is free to take another speed");
        assertEquals(10.20, state.referenceMoisture());

        state = step(state, 3, 68, 10.20, T0 + 2 * PROBE + POLL);
        assertEquals(2, state.stepDown());
        assertTrue(state.probing());
        assertEquals(1, HeatLossGuardPolicy.guardedSpeed(3, 0, state, CONFIG), "which is the floor");

        state = step(state, 3, 67, 10.00, T0 + 3 * PROBE + POLL);
        assertEquals(2, state.stepDown(), "and it was still drying at the floor");
        assertFalse(state.probing());

        state = step(state, 3, 67, 10.00, T0 + 3 * PROBE + 2 * POLL);
        assertEquals(2, state.stepDown(), "there is nothing left to take");
        assertFalse(state.probing());
        assertEquals(1, HeatLossGuardPolicy.guardedSpeed(3, 0, state, CONFIG));
    }

    @Test
    void aStalledProbeGivesOneSpeedBackAndHoldsThere() {
        HeatLossGuardPolicy.HeatLossState state = firstProbe(10.43, 10.43, 3);

        state = step(state, 3, 69, 10.40, T0 + 2 * PROBE);
        assertEquals(0, state.stepDown(), "0.03 g/kg is not progress, so the speed goes back");
        assertTrue(state.holding());
        assertFalse(state.probing());
        assertEquals(10.40, state.referenceMoisture());
        assertEquals(3, HeatLossGuardPolicy.guardedSpeed(3, 0, state, CONFIG));

        // A hold is not a probe with a longer window: no amount of waiting alone earns another step-down.
        for (long elapsed = POLL; elapsed <= 6 * PROBE; elapsed += POLL) {
            state = step(state, 3, 69, 10.40, T0 + 2 * PROBE + elapsed);
            assertEquals(0, state.stepDown(), "stepped down while holding, after " + elapsed + " ms");
            assertTrue(state.holding());
        }
    }

    @Test
    void aHoldIsReleasedOnlyByMoistureFallingUnaided() {
        HeatLossGuardPolicy.HeatLossState state = firstProbe(10.43, 10.43, 3);
        state = step(state, 3, 69, 10.40, T0 + 2 * PROBE);
        assertTrue(state.holding());

        state = step(state, 3, 68, 10.26, T0 + 3 * PROBE);
        assertTrue(state.holding(), "0.14 g/kg is one hundredth short of the release");

        state = step(state, 3, 68, 10.25, T0 + 4 * PROBE);
        assertFalse(state.holding(), "the house is drying again on its own");
        assertEquals(10.25, state.referenceMoisture());
        assertEquals(0, state.stepDown(), "the release itself takes nothing away");

        state = step(state, 3, 68, 10.25, T0 + 4 * PROBE + POLL);
        assertEquals(1, state.stepDown(), "and then the guard may probe again");
        assertTrue(state.probing());
    }

    @Test
    void aProbeIsScoredOnMoistureNotOnRelativeHumidity() {
        // The regression test for the whole reason HumidityPhysics exists. Both branches start from the same
        // probe, and in both the relative humidity RISES - which is what a cooling house does.
        double probeStart = HumidityPhysics.mixingRatioGramsPerKg(60, 20.0);
        double dried = HumidityPhysics.mixingRatioGramsPerKg(62, 19.0);
        double flat = HumidityPhysics.mixingRatioGramsPerKg(63, 19.0);
        assertTrue(probeStart - dried >= CONFIG.progressGramsPerKg(), "62 % at 19 C really is drier");
        assertTrue(probeStart - flat < CONFIG.progressGramsPerKg(), "63 % at 19 C is barely a change");

        HeatLossGuardPolicy.HeatLossState probing = firstProbe(probeStart, probeStart, 2);
        assertEquals(1, probing.stepDown());

        HeatLossGuardPolicy.HeatLossState progressed = step(probing, 2, 62, dried, T0 + 2 * PROBE);
        assertEquals(1, progressed.stepDown(), "an RH check would have called this a stall and stepped up");
        assertFalse(progressed.holding());

        HeatLossGuardPolicy.HeatLossState stalled = step(probing, 2, 63, flat, T0 + 2 * PROBE);
        assertEquals(0, stalled.stepDown(), "flat moisture is a stall however the RH reads");
        assertTrue(stalled.holding());
    }

    @Test
    void steadyDampAirEventuallyProbesOnceThePeakHasAged() {
        // The motivating case: 69 % all night with no event at all. Nothing is at a peak in any meaningful
        // sense, so the peak has to expire or the guard would never do anything here.
        HeatLossGuardPolicy.HeatLossState state = step(IDLE, 2, 69, STEADY_69, T0);

        for (long elapsed = POLL; elapsed < PROBE; elapsed += POLL) {
            state = step(state, 2, 69, STEADY_69, T0 + elapsed);
            assertEquals(0, state.stepDown(), "probed while the peak was fresh, after " + elapsed + " ms");
        }

        state = step(state, 2, 69, STEADY_69, T0 + PROBE);
        assertEquals(1, state.stepDown(), "ten minutes of no event at all is enough to try");
        assertTrue(state.probing());
        assertEquals(1, HeatLossGuardPolicy.guardedSpeed(2, 0, state, CONFIG));

        // And the honest outcome of that trial: air that does not dry out at the lower speed gets the speed
        // back. The guard only keeps what it can show is working.
        state = step(state, 2, 69, STEADY_69, T0 + 2 * PROBE);
        assertEquals(0, state.stepDown());
        assertTrue(state.holding());
        assertEquals(2, HeatLossGuardPolicy.guardedSpeed(2, 0, state, CONFIG));
    }

    @Test
    void anythingItCannotJudgeReleasesTheGuardEntirely() {
        HeatLossGuardPolicy.HeatLossState probing = firstProbe(10.43, 10.43, 3);
        assertEquals(1, probing.stepDown());

        assertReleased(step(probing, 3, 80, 12.12, T0 + PROBE + POLL), "mould risk outranks heat");
        assertReleased(HeatLossGuardPolicy.evaluate(probing, 3, 69, Double.NaN, INDOOR_C, OUTSIDE_C,
                T0 + PROBE + POLL, CONFIG), "an unmeasurable mixing ratio");
        assertReleased(HeatLossGuardPolicy.evaluate(probing, 3, 69, 10.43, 23.0, OUTSIDE_C,
                T0 + PROBE + POLL, CONFIG), "the house has warmed up to the ceiling");
        assertReleased(HeatLossGuardPolicy.evaluate(probing, 3, 69, 10.43, INDOOR_C, 18.0,
                T0 + PROBE + POLL, CONFIG), "a mild night costs little");
        assertReleased(step(probing, 1, 69, 10.43, T0 + PROBE + POLL), "the policy is already at the floor");
    }

    @Test
    void guardedSpeedNeverUndercutsCoolingOrTheFloor() {
        HeatLossGuardPolicy.HeatLossState twoSteps = firstProbe(10.43, 10.43, 3);
        twoSteps = step(twoSteps, 3, 68, 10.20, T0 + 2 * PROBE);
        twoSteps = step(twoSteps, 3, 68, 10.20, T0 + 2 * PROBE + POLL);
        assertEquals(2, twoSteps.stepDown());

        assertEquals(2, HeatLossGuardPolicy.guardedSpeed(3, 2, twoSteps, CONFIG),
                "evening cooling has its own indoor gate, so it is exempt");
        assertEquals(1, HeatLossGuardPolicy.guardedSpeed(3, 0, twoSteps, CONFIG));
        assertEquals(1, HeatLossGuardPolicy.guardedSpeed(2, 0, twoSteps, CONFIG),
                "never below the floor, however many steps were taken");
        assertEquals(3, HeatLossGuardPolicy.guardedSpeed(3, 0, IDLE, CONFIG));
        assertEquals(3, HeatLossGuardPolicy.guardedSpeed(3, 0, null, CONFIG), "no state means no limit");
        assertEquals(0, HeatLossGuardPolicy.guardedSpeed(0, 0, twoSteps, CONFIG),
                "a heat-loss limiter must never be the thing that starts the fan");
    }

    @Test
    void theGuardsOwnWriteCannotErodeTheVeryHighTargetItMeasuresAgainst() {
        // 79 % RH, one point below the mould override and inside the very-high deadband. The policy target is
        // 3 and stays 3; the guard's own write is 2, and if that write came back as the hysteresis latch the
        // target would fall to 2 and the guard would reach the floor four minutes into a ten-minute probe.
        double moisture = HumidityPhysics.mixingRatioGramsPerKg(79, INDOOR_C);
        assertEquals(3, policyTargetFor(79, 3));

        HeatLossGuardPolicy.HeatLossState state = step(IDLE, 3, 79, moisture, T0);
        state = step(state, policyTargetFor(79, 3), 79, moisture, T0 + PROBE);
        assertEquals(1, state.stepDown());
        assertEquals(2, HeatLossGuardPolicy.guardedSpeed(3, 0, state, CONFIG));

        assertEquals(2, policyTargetFor(79, 2), "feeding the guard's own write back in would drop the target");
        assertEquals(1, HeatLossGuardPolicy.guardedSpeed(2, 0, state, CONFIG),
                "and one step off that lowered target is already the floor");

        // With the target isolated, the whole probe window holds at 2 on the strength of the real target.
        for (long elapsed = POLL; elapsed < PROBE; elapsed += POLL) {
            state = step(state, policyTargetFor(79, 3), 79, moisture, T0 + PROBE + elapsed);
            assertEquals(1, state.stepDown(), "eroded after " + elapsed + " ms");
            assertEquals(2, HeatLossGuardPolicy.guardedSpeed(3, 0, state, CONFIG));
        }
    }

    @Test
    void theGuardsOwnWriteCannotCollapseTheHighTargetItMeasuresAgainst() {
        // 63 % RH, inside the high deadband. The policy target is 2; the guard's write is 1, and if that came
        // back as the latch the target would collapse to NORMAL_SPEED, disarm the guard, restore speed 2 and
        // start the cycle again - the oscillation this release exists to remove.
        double moisture = HumidityPhysics.mixingRatioGramsPerKg(63, INDOOR_C);
        assertEquals(2, policyTargetFor(63, 2));

        HeatLossGuardPolicy.HeatLossState state = step(IDLE, 2, 63, moisture, T0);
        state = step(state, policyTargetFor(63, 2), 63, moisture, T0 + PROBE);
        assertEquals(1, state.stepDown());
        assertEquals(1, HeatLossGuardPolicy.guardedSpeed(2, 0, state, CONFIG));

        HeatLossGuardPolicy.HeatLossState next =
                step(state, policyTargetFor(63, 2), 63, moisture, T0 + PROBE + POLL);
        assertTrue(next.probing(), "the probe survives its own write");
        assertEquals(1, next.stepDown());

        assertEquals(NORMAL_SPEED, policyTargetFor(63, 1), "what the guard's own write would have produced");
        HeatLossGuardPolicy.HeatLossState collapsed =
                step(state, policyTargetFor(63, 1), 63, moisture, T0 + PROBE + POLL);
        assertFalse(collapsed.probing(), "which would have thrown the probe away");
        assertEquals(0, collapsed.stepDown());
    }

    @Test
    void theLogFieldNamesTheStateInOneWord() {
        assertEquals("off", HeatLossGuardPolicy.describe(IDLE, T0));
        assertEquals("off", HeatLossGuardPolicy.describe(null, T0));

        HeatLossGuardPolicy.HeatLossState probing = firstProbe(10.43, 10.43, 3);
        assertEquals("step-1 probing 0m", HeatLossGuardPolicy.describe(probing, T0 + PROBE));
        assertEquals("step-1 probing 4m", HeatLossGuardPolicy.describe(probing, T0 + PROBE + 4 * 60_000L));

        // Down to two steps on real progress, then a stall gives one back and holds there.
        HeatLossGuardPolicy.HeatLossState held = step(probing, 3, 68, 10.20, T0 + 2 * PROBE);
        held = step(held, 3, 68, 10.20, T0 + 2 * PROBE + POLL);
        assertEquals(2, held.stepDown());
        held = step(held, 3, 68, 10.20, T0 + 3 * PROBE + POLL);
        assertEquals("step-1 held", HeatLossGuardPolicy.describe(held, T0 + 3 * PROBE + POLL));
    }

    @Test
    void aMissingStateIsTreatedAsIdleRatherThanCrashing() {
        HeatLossGuardPolicy.HeatLossState state = HeatLossGuardPolicy.evaluate(null, 3, 69, 10.43,
                INDOOR_C, OUTSIDE_C, T0, CONFIG);

        assertEquals(0, state.stepDown());
        assertEquals(10.43, state.peakMoisture());
        assertEquals(T0, state.peakTime());
    }

    /** One poll at the arming temperatures. */
    private static HeatLossGuardPolicy.HeatLossState step(HeatLossGuardPolicy.HeatLossState state,
            int policyTarget, int humidity, double moisture, long nowMillis) {
        return HeatLossGuardPolicy.evaluate(state, policyTarget, humidity, moisture, INDOOR_C, OUTSIDE_C,
                nowMillis, CONFIG);
    }

    /**
     * Drives the guard to its first probe the way an idle damp house does: seed the peak at {@code T0}, then
     * let it age past the probe window so the first step-down is taken at {@code T0 + PROBE}.
     */
    private static HeatLossGuardPolicy.HeatLossState firstProbe(double peak, double atProbeStart,
            int policyTarget) {
        HeatLossGuardPolicy.HeatLossState seeded = step(IDLE, policyTarget, 69, peak, T0);
        return step(seeded, policyTarget, 69, atProbeStart, T0 + PROBE);
    }

    /** What the unit's integer humidity reading is actually worth in g/kg at the observed indoor temperature. */
    private static double moistureAt(int humidityPct) {
        return HumidityPhysics.mixingRatioGramsPerKg(humidityPct, INDOOR_C);
    }

    /** The pre-guard policy target, through the real threshold chain, as {@code updateFanSpeed} computes it. */
    private static int policyTargetFor(int humidity, int latchedSpeed) {
        int veryHighSpeed = Math.max(3, NORMAL_SPEED);
        int effectiveVeryHigh = HumidityMonitor.effectiveThreshold(VERY_HIGH, HYSTERESIS,
                latchedSpeed >= veryHighSpeed);
        if (humidity >= effectiveVeryHigh) {
            return veryHighSpeed;
        }
        return HumidityMonitor.selectAutomaticSpeed(humidity, false, 0, LOW, HIGH, NORMAL_SPEED,
                latchedSpeed, HYSTERESIS);
    }

    private static void assertReleased(HeatLossGuardPolicy.HeatLossState state, String because) {
        assertEquals(0, state.stepDown(), because);
        assertFalse(state.probing(), because);
        assertFalse(state.holding(), because);
        assertEquals(3, HeatLossGuardPolicy.guardedSpeed(3, 0, state, CONFIG), because);
    }

    private static HeatLossGuardPolicy.HeatLossGuardConfig disabled() {
        return new HeatLossGuardPolicy.HeatLossGuardConfig(false, CONFIG.indoorCeilingC(),
                CONFIG.tempDeltaC(), CONFIG.probeWindowMillis(), CONFIG.progressGramsPerKg(),
                CONFIG.peakMarginGramsPerKg(), CONFIG.overrideHumidityPct(), CONFIG.floorSpeed());
    }
}
