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
        if (policyTarget > current.lastPolicyTarget()
                || !Double.isFinite(current.peakMoisture())
                || moistureGramsPerKg > current.peakMoisture()) {
            return new HeatLossState(0, moistureGramsPerKg, nowMillis, NOT_PROBING,
                    moistureGramsPerKg, false, policyTarget);
        }

        // 4. Moisture is still at its recent peak, so the event is still running: hold, and drop any probe
        //    the rise invalidated. The peak time is deliberately not refreshed here - otherwise moisture
        //    hovering just under the peak would pin the fan down for ever.
        boolean peakIsFresh = nowMillis - current.peakTime() < config.probeWindowMillis();
        if (peakIsFresh && moistureGramsPerKg >= current.peakMoisture() - config.peakMarginGramsPerKg()) {
            return new HeatLossState(current.stepDown(), current.peakMoisture(), current.peakTime(),
                    NOT_PROBING, current.referenceMoisture(), current.holding(), policyTarget);
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

    /** One short field for the per-poll fan decision log: {@code off}, {@code step-1 probing 4m}, {@code step-2 held}. */
    static String describe(HeatLossState state, long nowMillis) {
        if (state == null || state.stepDown() <= 0) {
            return "off";
        }
        String detail = "";
        if (state.probing()) {
            detail = " probing " + Math.max(0L, (nowMillis - state.probeStartTime()) / 60_000L) + "m";
        } else if (state.holding()) {
            detail = " held";
        }
        return "step-" + state.stepDown() + detail;
    }

    private static boolean isPlausible(double tempC) {
        return Double.isFinite(tempC) && tempC >= MIN_PLAUSIBLE_TEMP_C && tempC <= MAX_PLAUSIBLE_TEMP_C;
    }
}
