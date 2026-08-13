final class EveningCoolingPolicy {
    private static final double START_TEMPERATURE_DEADBAND_C = 0.5;
    private static final double START_OUTSIDE_ADVANTAGE_C = 2.0;
    private static final double CONTINUE_OUTSIDE_ADVANTAGE_C = 1.0;
    private static final double START_SUPPLY_ADVANTAGE_C = 1.0;
    private static final double CONTINUE_SUPPLY_ADVANTAGE_C = 0.5;
    private static final double IMMEDIATE_HIGH_SPEED_DELTA_C = 6.0;
    private static final double CONTINUE_HIGH_SPEED_DELTA_C = 5.5;
    private static final double IMMEDIATE_HIGH_SPEED_OVER_TARGET_C = 3.0;
    private static final double CONTINUE_HIGH_SPEED_OVER_TARGET_C = 2.5;

    private EveningCoolingPolicy() {
    }

    static int selectSpeed(
            int currentCoolingSpeed,
            double supplyTemp,
            double outsideTemp,
            double extractTemp,
            double targetIndoorTemp,
            double minimumSupplyTemp,
            boolean coolingStalled) {
        if (!areTemperaturesValid(supplyTemp, outsideTemp, extractTemp)
            || supplyTemp <= minimumSupplyTemp) {
            return 0;
        }

        double outsideAdvantage = extractTemp - outsideTemp;
        double supplyAdvantage = extractTemp - supplyTemp;
        boolean shouldStart = extractTemp >= targetIndoorTemp + START_TEMPERATURE_DEADBAND_C
                && outsideAdvantage >= START_OUTSIDE_ADVANTAGE_C
                && supplyAdvantage >= START_SUPPLY_ADVANTAGE_C;
        boolean shouldContinue = currentCoolingSpeed > 0
                && extractTemp > targetIndoorTemp
                && outsideAdvantage > CONTINUE_OUTSIDE_ADVANTAGE_C
                && supplyAdvantage > CONTINUE_SUPPLY_ADVANTAGE_C;

        if (!shouldStart && !shouldContinue) {
            return 0;
        }

        double highSpeedDelta = currentCoolingSpeed == 3
            ? CONTINUE_HIGH_SPEED_DELTA_C : IMMEDIATE_HIGH_SPEED_DELTA_C;
        double highSpeedOverTarget = currentCoolingSpeed == 3
            ? CONTINUE_HIGH_SPEED_OVER_TARGET_C : IMMEDIATE_HIGH_SPEED_OVER_TARGET_C;
        if (coolingStalled
            || outsideAdvantage >= highSpeedDelta
            || extractTemp >= targetIndoorTemp + highSpeedOverTarget) {
            return 3;
        }
        return 2;
    }

    static boolean hasStalled(double baselineIndoorTemp, double currentIndoorTemp,
            long elapsedMs, long escalationMs, double requiredProgressC) {
        return Double.isFinite(baselineIndoorTemp)
                && Double.isFinite(currentIndoorTemp)
                && elapsedMs >= escalationMs
                && currentIndoorTemp > baselineIndoorTemp - requiredProgressC;
    }

    private static boolean areTemperaturesValid(double... temperatures) {
        for (double temperature : temperatures) {
            if (!Double.isFinite(temperature) || temperature < -40.0 || temperature > 80.0) {
                return false;
            }
        }
        return true;
    }
}