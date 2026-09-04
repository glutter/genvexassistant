/**
 * Psychrometric helpers for the humidity control policies.
 *
 * <p>Relative humidity is temperature-dependent: cooling a room from 20 C to 19 C at 60 % RH raises the
 * reading by roughly four points without a drop of water being added or removed. Any check that has to
 * decide whether moisture is actually leaving the house therefore has to run on the mixing ratio - grams
 * of water per kilogram of dry air - rather than on the relative humidity the unit reports.
 *
 * <p>Condensation and mould risk are still relative-humidity questions, so those checks stay on RH.
 */
final class HumidityPhysics {
    /** Magnus/Tetens coefficients over liquid water, accurate to ~0.1 % between -40 C and 50 C. */
    private static final double MAGNUS_A_PA = 610.94;
    private static final double MAGNUS_B = 17.625;
    private static final double MAGNUS_C_C = 243.04;
    /**
     * Standard sea-level pressure. The unit reports no barometric pressure, and every caller compares
     * mixing ratios measured minutes apart, so the constant bias this introduces cancels out.
     */
    private static final double PRESSURE_PA = 101325.0;
    /** 1000 * M_water / M_dry_air, so the result comes out in grams per kilogram. */
    private static final double MIXING_RATIO_SCALE = 621.945;

    private HumidityPhysics() {
    }

    /** Saturation vapour pressure over water in Pa, or NaN for a non-finite temperature. */
    static double saturationVapourPressurePa(double tempC) {
        if (!Double.isFinite(tempC)) {
            return Double.NaN;
        }
        return MAGNUS_A_PA * Math.exp(MAGNUS_B * tempC / (MAGNUS_C_C + tempC));
    }

    /**
     * Mixing ratio in grams of water per kilogram of dry air.
     *
     * @param relativeHumidityPct relative humidity in percent; anything outside 0-100 (including the -1
     *                            the unit reports for a failed read) yields NaN
     * @param tempC               dry-bulb temperature; non-finite yields NaN
     * @return the mixing ratio, or NaN when it cannot be computed, so callers can fall back
     */
    static double mixingRatioGramsPerKg(int relativeHumidityPct, double tempC) {
        if (relativeHumidityPct < 0 || relativeHumidityPct > 100) {
            return Double.NaN;
        }
        double saturationPressure = saturationVapourPressurePa(tempC);
        if (!Double.isFinite(saturationPressure)) {
            return Double.NaN;
        }
        double vapourPressure = saturationPressure * relativeHumidityPct / 100.0;
        if (!Double.isFinite(vapourPressure) || vapourPressure >= PRESSURE_PA) {
            return Double.NaN;
        }
        return MIXING_RATIO_SCALE * vapourPressure / (PRESSURE_PA - vapourPressure);
    }
}
