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
}