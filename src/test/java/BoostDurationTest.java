import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BoostDurationTest {
    @Test
    void configuredWindowRemainsInBoostUntilItsEnd() {
        assertEquals(HumidityMonitor.HumidityRecoveryPhase.BOOST,
                HumidityMonitor.humidityRecoveryPhase(true, 999, 1_000));
    }

    @Test
    void recoveryStartsAtTheEndOfTheBoostWindow() {
        assertEquals(HumidityMonitor.HumidityRecoveryPhase.RECOVERY,
                HumidityMonitor.humidityRecoveryPhase(true, 1_000, 1_000));
        assertEquals(HumidityMonitor.HumidityRecoveryPhase.INACTIVE,
                HumidityMonitor.humidityRecoveryPhase(false, 999, 1_000));
    }

    @Test
    void boostDeadlineStartsWhenFanControlBegins() {
        assertEquals(16_000, HumidityMonitor.initializedBoostEndTime(0, 1_000, 15_000));
        assertEquals(2_000, HumidityMonitor.initializedBoostEndTime(2_000, 1_000, 15_000));
    }

    @Test
    void pendingBoostIsNotReportedAsExtended() {
        assertEquals(false, HumidityMonitor.isBoostExtended(true, 1_000, 0));
        assertEquals(true, HumidityMonitor.isBoostExtended(true, 1_000, 1_000));
        assertEquals(false, HumidityMonitor.isBoostExtended(false, 1_000, 500));
    }
}
