/**
 * Adaptive step-down for humidity-driven ventilation in cold weather.
 *
 * <p>Humidity control has no idea what it costs: at 20.6 C indoors and 14.6 C outside it will happily hold
 * speed 2 for hours because the reading is 69 %, and shower boost holds speed 3 until humidity returns to
 * its pre-shower baseline, which in winter can mean hours of 70 % duty pulling in cold air. A fixed timer
 * would cut moisture removal short whenever several people shower in sequence, so this is a closed loop
 * instead: step down one speed, watch whether the house is still drying, and only keep descending while it
 * demonstrably is.
 *
 * <p>Progress runs on the mixing ratio rather than on relative humidity. The guard arms precisely when the
 * house is cooling, and a 0.5 C drop across a ten-minute probe moves RH by about as much as the progress
 * threshold, in the wrong direction - an RH-based check would read "stalled" while drying was working, and
 * defeat the guard exactly when it matters. The mould override stays on RH, which is the right signal for
 * condensation at cold surfaces.
 *
 * <p>Every method is a pure function of its arguments, the way {@link EveningCoolingPolicy} is, so the whole
 * state machine is unit-testable without a unit or a database.
 */
final class HeatLossGuardPolicy {
    /** Sentinel for {@link HeatLossState#probeStartTime()}: no probe in flight. */
    static final long NOT_PROBING = 0L;

    private static final double MIN_PLAUSIBLE_TEMP_C = -40.0;
    private static final double MAX_PLAUSIBLE_TEMP_C = 80.0;

    private HeatLossGuardPolicy() {
    }

    /**
     * @param enabled              master switch
     * @param indoorCeilingC       above this indoor temperature the house does not need the heat, so the
     *                             guard never arms - this is what makes the feature winter-only without a
     *                             separate season switch
     * @param tempDeltaC           indoor minus outside must exceed this before ventilation is judged costly
     * @param probeWindowMillis    how long one step-down is observed before it is scored
     * @param progressGramsPerKg   mixing-ratio fall within a probe window that counts as still drying
     * @param peakMarginGramsPerKg how close to the peak counts as "the event is still going"
     * @param overrideHumidityPct  relative humidity at or above which the guard releases entirely
     * @param floorSpeed           the guard never commands below this fan speed
     */
    record HeatLossGuardConfig(boolean enabled, double indoorCeilingC, double tempDeltaC,
            long probeWindowMillis, double progressGramsPerKg, double peakMarginGramsPerKg,
            int overrideHumidityPct, int floorSpeed) {
    }

    /**
     * @param stepDown         speeds currently withheld from the policy target
     * @param peakMoisture     highest mixing ratio seen since the guard armed
     * @param peakTime         when {@code peakMoisture} was recorded; the peak expires after one probe
     *                         window, which is what lets a house that is simply damp - steady 69 % with no
     *                         event at all - ever start probing
     * @param probeStartTime   when the current probe began, or {@link #NOT_PROBING}
     * @param referenceMoisture the mixing ratio the current probe or hold is measured against
     * @param holding          a probe stalled, so no further step-downs until moisture falls unaided
     * @param lastPolicyTarget the target seen at the previous evaluation - a comparison point, not a
     *                         high-water mark, so a policy that asks for more air is recognised as a new event
     */
    record HeatLossState(int stepDown, double peakMoisture, long peakTime, long probeStartTime,
            double referenceMoisture, boolean holding, int lastPolicyTarget) {
        static final HeatLossState IDLE =
                new HeatLossState(0, Double.NaN, 0L, NOT_PROBING, Double.NaN, false, 0);

        boolean probing() {
            return probeStartTime != NOT_PROBING;
        }
    }

    /**
     * Whether ventilation is currently costing heat worth guarding. Temperatures that are missing or
     * implausible mean the cost cannot be judged, so the guard stands down and moisture control wins.
     */
    static boolean isArmed(double indoorTempC, double outsideTempC, HeatLossGuardConfig config) {
        return config != null
                && config.enabled()
                && isPlausible(indoorTempC)
                && isPlausible(outsideTempC)
                && indoorTempC < config.indoorCeilingC()
                && indoorTempC - outsideTempC > config.tempDeltaC();
    }

    /**
     * Advances the state machine by one poll. Nine ordered transitions, first match wins.
     *
     * @param policyTarget         the speed humidity control asked for, before the guard limited anything -
     *                             never the speed the guard itself wrote, or it would erode the target it
     *                             measures against
     * @param moistureGramsPerKg   indoor mixing ratio; NaN releases the guard rather than guessing
     * @param nowMillis            wall-clock millis, which must be positive - {@link #NOT_PROBING} is 0
     */
    static HeatLossState evaluate(HeatLossState state, int policyTarget, int humidityPct,
            double moistureGramsPerKg, double indoorTempC, double outsideTempC, long nowMillis,
            HeatLossGuardConfig config) {
        HeatLossState current = state == null ? HeatLossState.IDLE : state;

        // 1. Nothing to guard, nothing measurable, already at the floor, or mould risk outranks heat.
        if (!isArmed(indoorTempC, outsideTempC, config)
                || !Double.isFinite(moistureGramsPerKg)
                || policyTarget <= config.floorSpeed()
                || humidityPct >= config.overrideHumidityPct()) {
            return HeatLossState.IDLE;
        }

        // 2. The policy just asked for more air, so treat it as a fresh event and give it the full target.
        //    3. A new moisture peak - the next person's shower - does the same.
        //    3b. So does a RISE of a full peak margin above whatever level the guard last measured, even when
        //    it stays below an older, bigger peak. Without this the peak is an all-time high-water mark, and
        //    the second of two showers is invisible: the first one peaks at 11.20 g/kg, the guard descends,
        //    and a later shower taking the house from 9.97 to 11.04 - seven whole sensor counts - never beats
        //    11.20, so the fan stays at the floor through the entire event. Only two things could have saved
        //    it, and neither is reliable: a policy target that happens to cross an RH band, or humidity
        //    dropping to the mid band in between so transition 1 wipes the state. Steady winter damp gives
        //    neither. Comparing against referenceMoisture rather than the peak makes the detector a rising
        //    edge on the current level, which is what "the next person's shower" actually looks like.
        //    Reusing peakMarginGramsPerKg keeps the config surface unchanged and is the same idea read the
        //    other way round: within a margin of the peak is the same event, so a margin above the current
        //    level is a different one. It is also two integer RH counts, so single-count flicker cannot
        //    trigger it - and unlike a spurious step-DOWN, a spurious reset errs toward more ventilation and
        //    costs at most one probe window. It cannot ratchet either: the reset re-baselines the reference
        //    to this reading, so the next reset needs another full margin above THIS level, which moisture
        //    can only reach after genuinely falling first.
        if (policyTarget > current.lastPolicyTarget()
                || !Double.isFinite(current.peakMoisture())
                || moistureGramsPerKg > current.peakMoisture()
                || moistureGramsPerKg >= current.referenceMoisture() + config.peakMarginGramsPerKg()) {
            return new HeatLossState(0, moistureGramsPerKg, nowMillis, NOT_PROBING,
                    moistureGramsPerKg, false, policyTarget);
        }

        // 4. Moisture is still at its recent peak, so the event is still running: hold the current step, and
        //    carry any probe forward rather than cancelling it. Cancelling looks tidier but costs a whole
        //    speed: transition 7 only knows how to take ANOTHER one, so a probe dropped on the way back into
        //    the margin is replaced by a fresh step-down on the way back out, with no window ever scored. The
        //    margin is about two integer RH counts wide, so one count of sensor flicker straddles it and the
        //    fan would walk to the floor in three polls. Carried forward, the window is scored at its proper
        //    deadline, and a window containing a rise scores as a stall - which gives the speed back. The
        //    probe always starts after the peak, so the peak goes stale first and can never delay a scoring.
        //    The peak time is deliberately not refreshed here - otherwise moisture hovering just under the
        //    peak would pin the fan down for ever.
        boolean peakIsFresh = nowMillis - current.peakTime() < config.probeWindowMillis();
        if (peakIsFresh && moistureGramsPerKg >= current.peakMoisture() - config.peakMarginGramsPerKg()) {
            return new HeatLossState(current.stepDown(), current.peakMoisture(), current.peakTime(),
                    current.probeStartTime(), current.referenceMoisture(), current.holding(), policyTarget);
        }

        if (current.holding()) {
            // 5. Moisture fell further without extra ventilation, so the hold has earned its release.
            if (moistureGramsPerKg <= current.referenceMoisture() - config.progressGramsPerKg()) {
                return new HeatLossState(current.stepDown(), current.peakMoisture(), current.peakTime(),
                        NOT_PROBING, moistureGramsPerKg, false, policyTarget);
            }
            // 6. Still stalled.
            return new HeatLossState(current.stepDown(), current.peakMoisture(), current.peakTime(),
                    NOT_PROBING, current.referenceMoisture(), true, policyTarget);
        }

        if (!current.probing()) {
            // 7. Take one speed away and start observing. At the floor there is nothing left to take.
            if (policyTarget - current.stepDown() > config.floorSpeed()) {
                return new HeatLossState(current.stepDown() + 1, current.peakMoisture(), current.peakTime(),
                        nowMillis, moistureGramsPerKg, false, policyTarget);
            }
            return new HeatLossState(current.stepDown(), current.peakMoisture(), current.peakTime(),
                    NOT_PROBING, current.referenceMoisture(), false, policyTarget);
        }

        // 8. Observing; the window has not elapsed, so there is nothing to conclude yet.
        if (nowMillis - current.probeStartTime() < config.probeWindowMillis()) {
            return new HeatLossState(current.stepDown(), current.peakMoisture(), current.peakTime(),
                    current.probeStartTime(), current.referenceMoisture(), false, policyTarget);
        }

        // 9. Score the probe: still drying keeps the step and allows another, a stall gives one speed back
        //    and holds there.
        boolean progressed =
                current.referenceMoisture() - moistureGramsPerKg >= config.progressGramsPerKg();
        if (progressed) {
            return new HeatLossState(current.stepDown(), current.peakMoisture(), current.peakTime(),
                    NOT_PROBING, moistureGramsPerKg, false, policyTarget);
        }
        return new HeatLossState(Math.max(0, current.stepDown() - 1), current.peakMoisture(),
                current.peakTime(), NOT_PROBING, moistureGramsPerKg, true, policyTarget);
    }

    /**
     * The speed to command. Never above the policy target, never below the floor, and never below an active
     * evening-cooling speed - cooling has its own indoor-temperature gate and open-bypass requirement, so it
     * is exempt by construction.
     */
    static int guardedSpeed(int policyTarget, int coolingSpeed, HeatLossState state,
            HeatLossGuardConfig config) {
        int stepDown = state == null ? 0 : state.stepDown();
        int limited = Math.max(config.floorSpeed(), policyTarget - stepDown);
        return Math.max(coolingSpeed, Math.min(policyTarget, limited));
    }

    /**
     * One short field for the per-poll fan decision log. The whole vocabulary:
     *
     * <ul>
     *   <li>{@code off} - the guard is not engaged at all, which is exactly {@link HeatLossState#IDLE}
     *   <li>{@code armed} - engaged and watching, but withholding nothing and blocking nothing
     *   <li>{@code step-2} - two speeds withheld, between probes
     *   <li>{@code step-1 probing 4m} - a step-down under observation, four minutes in
     *   <li>{@code step-0 held} / {@code step-2 held} - stalled, so no further step-downs until moisture
     *       falls unaided. {@code step-0 held} is reachable: transition 9 can give the last speed back and
     *       still hold, and the hold is what stops the guard immediately taking that speed again.
     * </ul>
     *
     * <p>The state is keyed on {@code peakMoisture} rather than on {@code stepDown}, because a step-down of
     * zero is three different situations - not engaged, engaged and idle, engaged and holding - and reporting
     * all three as {@code off} hid the hold, which is the one that changes what the next poll may do.
     * {@code step-0 probing} is unreachable by construction: only transition 7 starts a probe, and it does so
     * while taking a speed.
     */
    static String describe(HeatLossState state, long nowMillis) {
        if (state == null || !Double.isFinite(state.peakMoisture())) {
            return "off";
        }
        String detail = "";
        if (state.probing()) {
            detail = " probing " + Math.max(0L, (nowMillis - state.probeStartTime()) / 60_000L) + "m";
        } else if (state.holding()) {
            detail = " held";
        } else if (state.stepDown() == 0) {
            return "armed";
        }
        return "step-" + state.stepDown() + detail;
    }

    /**
     * The event phrase for a state change, or {@code null} when nothing worth a line happened.
     *
     * <p>Kept here, as a pure function, purely so it can be tested: the caller in {@code HumidityMonitor}
     * only ever appends to the log, so a wrong phrase there is invisible to every test in the suite.
     *
     * <p>A fresh event is recognised by {@code peakTime == nowMillis} rather than by the step-down reaching
     * zero. Transitions 2 and 3 - the policy asking for more air, and a new moisture peak - are the only ones
     * that stamp the peak with the current poll's clock, and they are also the only ones that can hand speed
     * back while moisture is <em>rising</em>. Reading them as a hold release printed "moisture is falling
     * unaided again" on the same line as a reading that had just risen to a new peak.
     */
    static String describeTransition(HeatLossState previous, HeatLossState next, long nowMillis) {
        if (previous == null || next == null) {
            return null;
        }
        if (previous.stepDown() == next.stepDown() && previous.holding() == next.holding()) {
            return null;
        }
        if (!Double.isFinite(next.peakMoisture())) {
            return "released, humidity control has the fan back";
        }
        if (next.peakTime() == nowMillis) {
            return previous.stepDown() > next.stepDown()
                    ? "a fresh moisture event, handing every withheld speed back"
                    : "a fresh moisture event, so the hold no longer applies";
        }
        if (next.stepDown() > previous.stepDown()) {
            return "holding back one speed to see whether the house is still drying";
        }
        if (next.holding() && !previous.holding()) {
            return "drying stalled, giving one speed back and holding there";
        }
        return "hold released, moisture is falling unaided again";
    }

    private static boolean isPlausible(double tempC) {
        return Double.isFinite(tempC) && tempC >= MIN_PLAUSIBLE_TEMP_C && tempC <= MAX_PLAUSIBLE_TEMP_C;
    }
}
