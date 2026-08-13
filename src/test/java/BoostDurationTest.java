import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BoostDurationTest {
    @Test
    void capsMinimumAtConfiguredDurationWhenShorterThanTenMinutes() {
        assertEquals(5 * 60 * 1000L, HumidityMonitor.boostMinimumDurationMillis(5 * 60 * 1000L));
    }

    @Test
    void retainsTenMinuteMinimumForLongerBoosts() {
        assertEquals(10 * 60 * 1000L, HumidityMonitor.boostMinimumDurationMillis(30 * 60 * 1000L));
    }
}