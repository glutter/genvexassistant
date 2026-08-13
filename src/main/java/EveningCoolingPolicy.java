final class EveningCoolingPolicy {
    private static final double START_OUTSIDE_ADVANTAGE_C = 2.0;
    private static final double CONTINUE_OUTSIDE_ADVANTAGE_C = 1.0;
    private static final double START_SUPPLY_ADVANTAGE_C = 1.0;
    private static final double CONTINUE_SUPPLY_ADVANTAGE_C = 0.5;
    private static final double IMMEDIATE_HIGH_SPEED_DELTA_C = 6.0;
    private static final double CONTINUE_HIGH_SPEED_DELTA_C = 5.5;
    private static final double IMMEDIATE_HIGH_SPEED_OVER_START_C = 2.5;
    private static final double CONTINUE_HIGH_SPEED_OVER_START_C = 2.0;

    private EveningCoolingPolicy() {
    }

    static int selectSpeed(
            int currentCoolingSpeed,
            double supplyTemp,
            double outsideTemp,
            double extractTemp,
            double stopIndoorTemp,
            double startIndoorTemp,
            double minimumSupplyTemp,
            boolean coolingStalled) {
        if (!areTemperaturesValid(supplyTemp, outsideTemp, extractTemp)
            || startIndoorTemp < stopIndoorTemp
            || supplyTemp <= minimumSupplyTemp) {
            return 0;
        }

        double outsideAdvantage = extractTemp - outsideTemp;
        double supplyAdvantage = extractTemp - supplyTemp;
        boolean shouldStart = extractTemp >= startIndoorTemp
                && outsideAdvantage >= START_OUTSIDE_ADVANTAGE_C
                && supplyAdvantage >= START_SUPPLY_ADVANTAGE_C;
        boolean shouldContinue = currentCoolingSpeed > 0
            && extractTemp > stopIndoorTemp
                && outsideAdvantage > CONTINUE_OUTSIDE_ADVANTAGE_C
                && supplyAdvantage > CONTINUE_SUPPLY_ADVANTAGE_C;

        if (!shouldStart && !shouldContinue) {
            return 0;
        }

        double highSpeedDelta = currentCoolingSpeed == 3
            ? CONTINUE_HIGH_SPEED_DELTA_C : IMMEDIATE_HIGH_SPEED_DELTA_C;
        double highSpeedOverStart = currentCoolingSpeed == 3
            ? CONTINUE_HIGH_SPEED_OVER_START_C : IMMEDIATE_HIGH_SPEED_OVER_START_C;
        if (coolingStalled
            || outsideAdvantage >= highSpeedDelta
            || extractTemp >= startIndoorTemp + highSpeedOverStart) {
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