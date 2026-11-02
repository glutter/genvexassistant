import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Shutdown races and /healthz edge cases found in review; each failed before the fix. */
class LifecycleRaceTest {

    private static final class RecordingMonitor extends HumidityMonitor {
        final List<Integer> writes = new CopyOnWriteArrayList<>();

        RecordingMonitor() {
            super("unused", "unused");
        }

        @Override
        void setFanSpeedImmediately(int speed) {
            writes.add(speed);
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = HumidityMonitor.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    /** Stands in for a device poll: holds clientLock until released. */
    private static Thread holdClientLock(HumidityMonitor monitor, CountDownLatch release) throws Exception {
        Object lock = field(monitor, "clientLock");
        CountDownLatch holding = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (lock) {
                holding.countDown();
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "fake-poll");
        holder.start();
        assertTrue(holding.await(5, TimeUnit.SECONDS));
        return holder;
    }

    private static Thread callHandler(com.sun.net.httpserver.HttpHandler handler, Exchange exchange) {
        Thread thread = new Thread(() -> {
            try {
                handler.handle(exchange);
            } catch (IOException ignored) {
                // not under test
            }
        }, "http-worker-fake");
        thread.start();
        return thread;
    }

    private static void waitUntilBlocked(Thread thread) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertEquals(Thread.State.BLOCKED, thread.getState(), "handler should be waiting on clientLock");
    }

    // A manual boost accepted before SIGTERM, but still waiting on the poll's clientLock, writes the fan once the
    // shutdown has aborted the poll and released the lock.
    @Test
    void manualBoostQueuedBehindAPollWritesTheFanAfterShutdownBegan() throws Exception {
        RecordingMonitor monitor = new RecordingMonitor();
        CountDownLatch release = new CountDownLatch(1);
        Thread poll = holdClientLock(monitor, release);
        Thread handler = callHandler(monitor.new UdluftningApiHandler(),
                new Exchange("POST", "/api/fan/udluftning", "{\"level\":4,\"duration_minutes\":30}"));
        waitUntilBlocked(handler);

        monitor.shutdown(500);          // what the shutdown hook does
        release.countDown();            // the poll is aborted by client.disconnect() and lets go
        handler.join(5_000);
        poll.join();

        assertTrue(monitor.writes.isEmpty(), "fan written after shutdown began: " + monitor.writes);
    }

    @Test
    void staticModeQueuedBehindAPollWritesTheFanAfterShutdownBegan() throws Exception {
        RecordingMonitor monitor = new RecordingMonitor();
        CountDownLatch release = new CountDownLatch(1);
        Thread poll = holdClientLock(monitor, release);
        Thread handler = callHandler(monitor.new StaticRpmApiHandler(),
                new Exchange("POST", "/api/fan/static", "{\"enabled\":true,\"speed\":0}"));
        waitUntilBlocked(handler);

        monitor.shutdown(500);
        release.countDown();
        handler.join(5_000);
        poll.join();

        assertTrue(monitor.writes.isEmpty(), "fan written after shutdown began: " + monitor.writes);
    }

    // The shutdown only waits for a restart that is already flagged. One accepted afterwards starts a sequence
    // (fan 0, pause, fan 1) in a JVM that is about to halt: the fan can be left stopped.
    @Test
    void restartAcceptedAfterTheShutdownWaitIsNotStartedWhileTheJvmExits() throws Exception {
        RecordingMonitor monitor = new RecordingMonitor();
        CountDownLatch release = new CountDownLatch(1);
        Thread poll = holdClientLock(monitor, release);
        Thread handler = callHandler(monitor.new RestartApiHandler(), new Exchange("POST", "/api/system/restart", ""));
        waitUntilBlocked(handler);

        monitor.shutdown(500);          // restartInProgress is still false here, so nothing is waited for
        release.countDown();
        handler.join(5_000);
        poll.join();
        Thread.sleep(300);

        assertTrue(monitor.writes.isEmpty(), "restart sequence started after shutdown completed: " + monitor.writes);
    }

    private static final class FakeClient extends GenvexClient {
        final List<String> events = new CopyOnWriteArrayList<>();
        final AtomicBoolean afterShutdown = new AtomicBoolean();
        final List<Integer> writes = new CopyOnWriteArrayList<>();
        final AtomicBoolean writeAfterShutdown = new AtomicBoolean();

        FakeClient() {
            super("unused", "unused");
        }

        @Override public void connect() { events.add(afterShutdown.get() ? "connect-after-shutdown" : "connect"); }
        @Override public void disconnect() { }

        @Override
        public int readDatapoint(int addr) {
            return readDatapoint(addr, 1);
        }

        @Override
        int readDatapoint(int addr, int retries) {
            return switch (addr) {
                case 26 -> 95;      // humidity: very high, so the policy wants speed 3
                case 20, 21, 22, 23 -> 500;
                case 35, 36 -> 2000;
                case 18 -> 5000;
                case 24 -> 1;       // setpoint read-back
                case 53 -> 0;
                default -> 0;
            };
        }

        @Override
        public void setFanSpeed(int speed) {
            writes.add(speed);
            if (afterShutdown.get()) writeAfterShutdown.set(true);
        }
    }

    // A poll that was waiting for clientLock when SIGTERM arrived reconnects (pollWithFreshConnection starts with
    // disconnect()+connect()) and runs a full control cycle, including a fan write.
    @Test
    void aPollWaitingOnTheLockReconnectsAndWritesTheFanAfterShutdownBegan() throws Exception {
        HumidityMonitor monitor = new HumidityMonitor("unused", "unused");
        FakeClient fake = new FakeClient();
        Field client = HumidityMonitor.class.getDeclaredField("client");
        client.setAccessible(true);
        client.set(monitor, fake);

        CountDownLatch release = new CountDownLatch(1);
        Thread holder = holdClientLock(monitor, release);   // e.g. a handler's slow setFanSpeedImmediately
        Thread poll = new Thread(monitor::pollAndStore, "scheduler-poll");
        poll.start();
        waitUntilBlocked(poll);

        fake.afterShutdown.set(true);
        monitor.shutdown(500);
        release.countDown();
        poll.join(5_000);
        holder.join();

        assertTrue(fake.events.stream().noneMatch("connect-after-shutdown"::equals) && fake.writes.isEmpty(),
                "events " + fake.events + ", fan writes " + fake.writes);
    }

    // The deploy gate asks for /healthz?require=device; any extra parameter silently turns the device check off.
    @Test
    void requireDeviceIsHonouredWithOtherQueryParameters() throws Exception {
        HumidityMonitor monitor = new HumidityMonitor("unused", "unused");
        Field failed = HumidityMonitor.class.getDeclaredField("lastPollFailed");
        failed.setAccessible(true);
        failed.setBoolean(monitor, true);                     // device status "failed"

        Exchange exact = new Exchange("GET", "/healthz?require=device", "");
        monitor.new HealthApiHandler().handle(exact);
        Exchange extra = new Exchange("GET", "/healthz?require=device&cb=1", "");
        monitor.new HealthApiHandler().handle(extra);

        assertEquals(503, exact.status);
        assertEquals(503, extra.status, "device failed but /healthz?require=device&cb=1 answered " + extra.status);
    }

    // Wall-clock based: a clock stepped back hides a dead poll loop for as long as the step.
    @Test
    void aBackwardsClockStepDoesNotHideADeadPollLoop() {
        long start = 1_000_000_000_000L;
        long lastPoll = start + 60_000;
        // 20 minutes of real time have passed since the last poll, but the wall clock was stepped back 1 hour.
        long now = lastPoll + 20 * 60_000 - 3_600_000;

        HealthReport report = HealthReport.evaluate(now, start, lastPoll, HealthReport.maxPollAgeMillis(30), false, 0,
                false, "ok", "x", "native");

        assertTrue(report.problems().contains("poll_loop_stalled"), "problems " + report.problems());
    }

    static final class Exchange extends HttpExchange {
        private final String method;
        private final URI uri;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final byte[] request;
        int status;

        Exchange(String method, String target, String body) {
            this.method = method;
            this.uri = URI.create(target);
            this.request = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { }
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(request); }
        @Override public OutputStream getResponseBody() { return out; }
        @Override public void sendResponseHeaders(int code, long length) { status = code; }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public int getResponseCode() { return status; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) { }
        @Override public void setStreams(InputStream input, OutputStream output) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}
