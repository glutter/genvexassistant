import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ShutdownTest {
    private static final class RecordingMonitor extends HumidityMonitor {
        final List<Integer> writes = new CopyOnWriteArrayList<>();
        final CountDownLatch firstWrite = new CountDownLatch(1);

        RecordingMonitor() {
            super("unused", "unused");
        }

        @Override
        void setFanSpeedImmediately(int speed) throws IOException, InterruptedException {
            writes.add(speed);
            firstWrite.countDown();
        }
    }

    private static AtomicBoolean restartFlag(HumidityMonitor monitor) throws Exception {
        java.lang.reflect.Field field = HumidityMonitor.class.getDeclaredField("restartInProgress");
        field.setAccessible(true);
        return (AtomicBoolean) field.get(monitor);
    }

    @Test
    void shutdownMakesHealthUnavailableAndIsIdempotent() {
        HumidityMonitor monitor = new HumidityMonitor("unused", "unused");
        assertFalse(monitor.healthReport(System.currentTimeMillis()).problems().contains("shutting_down"));

        monitor.shutdown(500);
        monitor.shutdown(500);

        assertTrue(monitor.healthReport(System.currentTimeMillis()).problems().contains("shutting_down"));
    }

    @Test
    void shutdownDuringARestartSequenceStillLeavesTheFanOnSpeedOneQuickly() throws Exception {
        RecordingMonitor monitor = new RecordingMonitor();
        restartFlag(monitor).set(true);
        Thread sequence = new Thread(monitor::runRestartSequence);
        long started = System.nanoTime();
        sequence.start();
        assertTrue(monitor.firstWrite.await(5, TimeUnit.SECONDS));

        monitor.shutdown(6_000);
        sequence.join(6_000);

        assertEquals(List.of(0, 1), monitor.writes);
        assertFalse(restartFlag(monitor).get());
        // Without the cut-short pauses the sequence alone needs ten seconds.
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 4_000);
    }

    @Test
    void shutdownGivesUpWaitingForAStuckRestartWithinItsBudget() throws Exception {
        HumidityMonitor monitor = new HumidityMonitor("unused", "unused");
        restartFlag(monitor).set(true);
        long started = System.nanoTime();

        monitor.shutdown(300);

        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(elapsed >= 250 && elapsed < 3_000, "elapsed " + elapsed);
    }

    @Test
    void shutdownWritesNoFanSpeedToTheUnit() {
        RecordingMonitor monitor = new RecordingMonitor();

        monitor.shutdown(500);

        assertTrue(monitor.writes.isEmpty());
    }

    @Test
    void shutdownStopsTheScheduler() throws Exception {
        HumidityMonitor monitor = new HumidityMonitor("unused", "unused");
        java.lang.reflect.Field field = HumidityMonitor.class.getDeclaredField("scheduler");
        field.setAccessible(true);
        java.util.concurrent.ScheduledExecutorService scheduler =
                (java.util.concurrent.ScheduledExecutorService) field.get(monitor);

        monitor.shutdown(500);

        assertTrue(scheduler.isShutdown());
    }
}
