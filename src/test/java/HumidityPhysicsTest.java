import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HumidityPhysicsTest {
    /** Published psychrometric-table values round to a tenth of a gram, which is the accuracy claimed. */
    private static final double TABLE_TOLERANCE = 0.1;

    @Test
    void matchesPublishedPsychrometricTables() {
        assertEquals(8.7, HumidityPhysics.mixingRatioGramsPerKg(60, 20.0), TABLE_TOLERANCE);
        assertEquals(14.7, HumidityPhysics.mixingRatioGramsPerKg(100, 20.0), TABLE_TOLERANCE);
        assertEquals(1.9, HumidityPhysics.mixingRatioGramsPerKg(50, 0.0), TABLE_TOLERANCE);
        assertEquals(2.3, HumidityPhysics.mixingRatioGramsPerKg(30, 10.0), TABLE_TOLERANCE);
        assertEquals(16.0, HumidityPhysics.mixingRatioGramsPerKg(60, 30.0), TABLE_TOLERANCE);
    }

    @Test
    void dryAirHoldsNoWaterAndSaturationRisesWithTemperature() {
        assertEquals(0.0, HumidityPhysics.mixingRatioGramsPerKg(0, 20.0));
        assertTrue(HumidityPhysics.saturationVapourPressurePa(20.0)
                > HumidityPhysics.saturationVapourPressurePa(19.0));
    }

    @Test
    void isMonotonicInBothArguments() {
        for (int humidity = 1; humidity <= 100; humidity++) {
            assertTrue(HumidityPhysics.mixingRatioGramsPerKg(humidity, 20.0)
                    > HumidityPhysics.mixingRatioGramsPerKg(humidity - 1, 20.0),
                    "moisture must rise with humidity at " + humidity + " %");
        }
        for (double tempC = -20.0; tempC <= 40.0; tempC += 1.0) {
            assertTrue(HumidityPhysics.mixingRatioGramsPerKg(50, tempC + 1.0)
                    > HumidityPhysics.mixingRatioGramsPerKg(50, tempC),
                    "moisture must rise with temperature at " + tempC + " C");
        }
    }

    @Test
    void theGuardThresholdsKeepTheSensitivityTheyWereChosenWith() {
        // The user chose 1 % and 2 % RH at ordinary indoor conditions; these are those values translated.
        double base = HumidityPhysics.mixingRatioGramsPerKg(60, 20.0);
        assertEquals(0.15, HumidityPhysics.mixingRatioGramsPerKg(61, 20.0) - base, 0.005);
        assertEquals(0.30, HumidityPhysics.mixingRatioGramsPerKg(62, 20.0) - base, 0.01);
    }

    @Test
    void coolingOneDegreeRaisesRelativeHumidityAboutFourPointsAtConstantMoisture() {
        // This is the defect the mixing ratio exists to avoid: an RH-based progress check would read
        // "wetter than when the probe started" purely because the guard is doing its job.
        double before = HumidityPhysics.mixingRatioGramsPerKg(60, 20.0);
        double afterThreePoints = HumidityPhysics.mixingRatioGramsPerKg(63, 19.0);
        double afterFourPoints = HumidityPhysics.mixingRatioGramsPerKg(64, 19.0);

        assertTrue(afterThreePoints < before, "3 points of the rise is still net drying");
        assertTrue(afterFourPoints > before, "4 points of the rise is more than the cooling accounts for");
        assertEquals(0.0, afterFourPoints - before, 0.05);
    }

    @Test
    void unusableInputsYieldNaNSoCallersCanFallBack() {
        assertTrue(Double.isNaN(HumidityPhysics.mixingRatioGramsPerKg(-1, 20.0)),
                "-1 is what the unit reports for a failed humidity read");
        assertTrue(Double.isNaN(HumidityPhysics.mixingRatioGramsPerKg(101, 20.0)));
        assertTrue(Double.isNaN(HumidityPhysics.mixingRatioGramsPerKg(60, Double.NaN)),
                "NaN is what a failed temperature read converts to");
        assertTrue(Double.isNaN(HumidityPhysics.mixingRatioGramsPerKg(60, Double.POSITIVE_INFINITY)));
        assertTrue(Double.isNaN(HumidityPhysics.saturationVapourPressurePa(Double.NaN)));
    }

    @Test
    void absurdTemperaturesNeverProduceAFiniteMoistureThatCouldBeCompared() {
        // Nothing should be able to hand the guard a plausible-looking number from nonsense input, including
        // the temperature at which the Magnus denominator vanishes.
        double atThePole = HumidityPhysics.mixingRatioGramsPerKg(60, -243.04);
        assertTrue(!Double.isFinite(atThePole) || atThePole <= 0.001,
                "expected no measurable moisture at -243 C, got " + atThePole);
        assertTrue(Double.isNaN(HumidityPhysics.mixingRatioGramsPerKg(60, 300.0)),
                "above the boiling point the vapour pressure exceeds atmospheric");
    }
}
