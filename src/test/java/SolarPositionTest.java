import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class SolarPositionTest {
    private static final double LAT = 55.676;
    private static final double LON = 12.568;
    private static final ZoneId COPENHAGEN = ZoneId.of("Europe/Copenhagen");

    // Reference values computed with astral 3.2 for Copenhagen: geometric elevation and the -0.833 degree sunrise/sunset.
    @Test
    void elevationMatchesAnIndependentReferenceWithinAFewHundredthsOfADegree() {
        assertEquals(56.545, elevation("2026-06-21T12:00:00Z"), 0.05);
        assertEquals(-1.050, elevation("2026-06-21T20:00:00Z"), 0.05);
        assertEquals(10.869, elevation("2026-12-21T11:00:00Z"), 0.05);
        assertEquals(-2.561, elevation("2026-03-20T05:00:00Z"), 0.05);
        assertEquals(-3.797, elevation("2026-09-22T17:30:00Z"), 0.05);
    }

    @Test
    void sunriseAndSunsetFallWithinTwoMinutesOfTheReference() {
        assertTransition("2026-03-20T05:12:33Z", "2026-03-20T17:22:57Z");
        assertTransition("2026-06-21T02:25:47Z", "2026-06-21T19:57:17Z");
        assertTransition("2026-09-22T04:55:08Z", "2026-09-22T17:08:39Z");
        assertTransition("2026-12-21T07:37:34Z", "2026-12-21T14:37:59Z");
    }

    @Test
    void midsummerSunsetIsHoursAfterTheFixedEveningWindowOpens() {
        // 21:30 local on midsummer: still light, though 18:00-06:30 would already call it evening.
        Instant evening = Instant.parse("2026-06-21T19:30:00Z");
        HumidityMonitor withSun = new HumidityMonitor("unused", "unused", Clock.fixed(evening, COPENHAGEN),
                new HostingConfig.Coordinates(LAT, LON));
        HumidityMonitor withoutSun = new HumidityMonitor("unused", "unused", Clock.fixed(evening, COPENHAGEN), null);
        LocalTime local = LocalTime.of(21, 30);

        assertFalse(withSun.isAfterSunset(local));
        assertTrue(withoutSun.isAfterSunset(local));
    }

    @Test
    void localSunsetFollowsTheInjectedClockAcrossTheDay() {
        HostingConfig.Coordinates copenhagen = new HostingConfig.Coordinates(LAT, LON);
        assertFalse(afterSunset("2026-06-21T12:00:00Z", copenhagen));
        assertTrue(afterSunset("2026-06-21T20:30:00Z", copenhagen));
        assertTrue(afterSunset("2026-06-21T01:00:00Z", copenhagen));
        assertTrue(afterSunset("2026-12-21T15:30:00Z", copenhagen));
        assertFalse(afterSunset("2026-12-21T12:00:00Z", copenhagen));
    }

    private static boolean afterSunset(String instant, HostingConfig.Coordinates location) {
        Instant at = Instant.parse(instant);
        Clock clock = Clock.fixed(at, COPENHAGEN);
        return new HumidityMonitor("unused", "unused", clock, location).isAfterSunset(LocalTime.now(clock));
    }

    private static double elevation(String instant) {
        return SolarPosition.elevationDegrees(Instant.parse(instant), LAT, LON);
    }

    private static void assertTransition(String sunrise, String sunset) {
        Instant rise = Instant.parse(sunrise);
        Instant set = Instant.parse(sunset);
        assertTrue(SolarPosition.isBelowHorizon(rise.minus(2, ChronoUnit.MINUTES), LAT, LON), "before " + sunrise);
        assertFalse(SolarPosition.isBelowHorizon(rise.plus(2, ChronoUnit.MINUTES), LAT, LON), "after " + sunrise);
        assertFalse(SolarPosition.isBelowHorizon(set.minus(2, ChronoUnit.MINUTES), LAT, LON), "before " + sunset);
        assertTrue(SolarPosition.isBelowHorizon(set.plus(2, ChronoUnit.MINUTES), LAT, LON), "after " + sunset);
    }
}
