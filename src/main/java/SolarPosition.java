import java.time.Instant;

/**
 * Solar elevation from the NOAA general solar position formulas (accurate to well under a minute of sunset
 * time at Danish latitudes). No dependencies, so it also runs where Home Assistant's sun.sun is not available.
 */
final class SolarPosition {
    /** Standard sunrise/sunset definition: solar disc radius plus atmospheric refraction. Matches sun.sun. */
    static final double HORIZON_ELEVATION_DEGREES = -0.833;

    private SolarPosition() {}

    static boolean isBelowHorizon(Instant instant, double latitudeDegrees, double longitudeDegrees) {
        return elevationDegrees(instant, latitudeDegrees, longitudeDegrees) < HORIZON_ELEVATION_DEGREES;
    }

    /** Geometric elevation of the sun's centre in degrees. Latitude is positive north, longitude positive east. */
    static double elevationDegrees(Instant instant, double latitudeDegrees, double longitudeDegrees) {
        long epochMillis = instant.toEpochMilli();
        double julianDay = epochMillis / 86_400_000.0 + 2_440_587.5;
        double t = (julianDay - 2_451_545.0) / 36_525.0;

        double meanLongitude = normalizeDegrees(280.46646 + t * (36_000.76983 + t * 0.0003032));
        double meanAnomaly = 357.52911 + t * (35_999.05029 - 0.0001537 * t);
        double eccentricity = 0.016708634 - t * (0.000042037 + 0.0000001267 * t);
        double anomalyRad = Math.toRadians(meanAnomaly);
        double centre = Math.sin(anomalyRad) * (1.914602 - t * (0.004817 + 0.000014 * t))
                + Math.sin(2 * anomalyRad) * (0.019993 - 0.000101 * t)
                + Math.sin(3 * anomalyRad) * 0.000289;
        double trueLongitude = meanLongitude + centre;
        double apparentLongitude = trueLongitude - 0.00569
                - 0.00478 * Math.sin(Math.toRadians(125.04 - 1_934.136 * t));

        double meanObliquity = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0;
        double obliquity = meanObliquity + 0.00256 * Math.cos(Math.toRadians(125.04 - 1_934.136 * t));
        double obliquityRad = Math.toRadians(obliquity);
        double declination = Math.asin(Math.sin(obliquityRad) * Math.sin(Math.toRadians(apparentLongitude)));

        double y = Math.tan(obliquityRad / 2) * Math.tan(obliquityRad / 2);
        double meanLongitudeRad = Math.toRadians(meanLongitude);
        double equationOfTimeMinutes = 4 * Math.toDegrees(y * Math.sin(2 * meanLongitudeRad)
                - 2 * eccentricity * Math.sin(anomalyRad)
                + 4 * eccentricity * y * Math.sin(anomalyRad) * Math.cos(2 * meanLongitudeRad)
                - 0.5 * y * y * Math.sin(4 * meanLongitudeRad)
                - 1.25 * eccentricity * eccentricity * Math.sin(2 * anomalyRad));

        double utcMinutes = Math.floorMod(epochMillis, 86_400_000L) / 60_000.0;
        double trueSolarMinutes = (utcMinutes + equationOfTimeMinutes + 4 * longitudeDegrees) % 1_440.0;
        double hourAngleRad = Math.toRadians(trueSolarMinutes / 4.0 - 180.0);

        double latitudeRad = Math.toRadians(latitudeDegrees);
        double cosZenith = Math.sin(latitudeRad) * Math.sin(declination)
                + Math.cos(latitudeRad) * Math.cos(declination) * Math.cos(hourAngleRad);
        return 90.0 - Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cosZenith))));
    }

    private static double normalizeDegrees(double degrees) {
        double result = degrees % 360.0;
        return result < 0 ? result + 360.0 : result;
    }
}
