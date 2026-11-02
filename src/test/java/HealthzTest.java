import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HealthzTest {
    private static final long START = 1_000_000_000_000L;
    private static final long WINDOW = HealthReport.maxPollAgeMillis(30);

    private static HealthReport report(long now, long lastPoll, boolean dbStartFailed, int dbErrors,
            boolean shuttingDown, String device) {
        return HealthReport.evaluate(now, START, lastPoll, WINDOW, dbStartFailed, dbErrors, shuttingDown, device,
                "1.83", "native");
    }

    @Test
    void pollWindowIsFiveMinutesOrTenPollIntervals() {
        assertEquals(300_000L, HealthReport.maxPollAgeMillis(30));
        assertEquals(300_000L, HealthReport.maxPollAgeMillis(1));
        assertEquals(600_000L, HealthReport.maxPollAgeMillis(60));
    }

    @Test
    void aRecentPollWithAHealthyDatabaseIsHealthyEvenWhenTheDeviceIsDown() {
        HealthReport healthy = report(START + 100_000, START + 90_000, false, 0, false, "failed");

        assertTrue(healthy.problems().isEmpty());
        assertTrue(healthy.healthy(false));
        assertFalse(healthy.healthy(true));
    }

    @Test
    void eachProcessProblemMakesTheReportUnhealthy() {
        long now = START + 100_000;
        long recent = START + 95_000;

        assertEquals(java.util.List.of("shutting_down"), report(now, recent, false, 0, true, "ok").problems());
        assertEquals(java.util.List.of("database_start_failed"), report(now, recent, true, 0, false, "ok").problems());
        assertEquals(java.util.List.of("database_errors"), report(now, recent, false, 5, false, "ok").problems());
        assertTrue(report(now, recent, false, 4, false, "ok").healthy(false));
        assertEquals(java.util.List.of("poll_loop_stalled"),
                report(START + 700_000, START + 100_000, false, 0, false, "ok").problems());
    }

    @Test
    void beforeTheFirstPollTheProcessStartIsTheReference() {
        assertTrue(report(START + 299_000, 0, false, 0, false, "awaiting").healthy(false));
        assertFalse(report(START + 301_000, 0, false, 0, false, "awaiting").healthy(false));
        assertEquals(-1, report(START + 1_000, 0, false, 0, false, "awaiting").lastPollAgeSeconds());
    }

    @Test
    void jsonNamesTheDeviceAndTheProblems() {
        HealthReport report = report(START + 100_000, START + 90_000, false, 0, false, "stale");

        assertEquals("{\"status\":\"ok\",\"version\":\"1.83\",\"profile\":\"native\",\"uptime_seconds\":100,"
                + "\"last_poll_age_seconds\":10,\"device\":\"stale\",\"problems\":[]}", report.json(false));
        assertEquals("{\"status\":\"unavailable\",\"version\":\"1.83\",\"profile\":\"native\",\"uptime_seconds\":100,"
                + "\"last_poll_age_seconds\":10,\"device\":\"stale\",\"problems\":[\"device_not_ok\"]}",
                report.json(true));
    }

    @Test
    void deviceStatusFollowsTheLastPoll() {
        assertEquals("failed", HealthReport.deviceStatus(true, 0, true, 75_000));
        assertEquals("failed", HealthReport.deviceStatus(true, 0, false, 75_000));
        assertEquals("awaiting", HealthReport.deviceStatus(false, 0, false, 75_000));
        assertEquals("ok", HealthReport.deviceStatus(false, 75_000, true, 75_000));
        assertEquals("stale", HealthReport.deviceStatus(false, 75_001, true, 75_000));
    }

    @Test
    void endpointAnswersWithoutTakingTheClientLockAndSupportsHead() throws Exception {
        HumidityMonitor monitor = new HumidityMonitor("unused", "unused");
        Object lock = field(monitor, "clientLock");
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (lock) {
                holding.countDown();
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        holder.start();
        try {
            holding.await();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                Exchange get = new Exchange("GET", "/healthz");
                monitor.new HealthApiHandler().handle(get);
                assertEquals(200, get.status);
                assertTrue(get.body().contains("\"status\":\"ok\""));
                assertTrue(get.body().contains("\"device\":\"awaiting\""));

                Exchange head = new Exchange("HEAD", "/healthz");
                monitor.new HealthApiHandler().handle(head);
                assertEquals(200, head.status);
                assertEquals("", head.body());
            });
        } finally {
            release.countDown();
            holder.join();
        }
    }

    @Test
    void endpointRejectsOtherMethodsAndPathsAndReports503ForProcessProblems() throws Exception {
        HumidityMonitor monitor = new HumidityMonitor("unused", "unused");

        Exchange post = new Exchange("POST", "/healthz");
        monitor.new HealthApiHandler().handle(post);
        assertEquals(405, post.status);
        Exchange other = new Exchange("GET", "/healthz/extra");
        monitor.new HealthApiHandler().handle(other);
        assertEquals(404, other.status);

        Exchange requireDevice = new Exchange("GET", "/healthz?require=device");
        monitor.new HealthApiHandler().handle(requireDevice);
        assertEquals(503, requireDevice.status);
        assertTrue(requireDevice.body().contains("device_not_ok"));

        java.lang.reflect.Field failed = HumidityMonitor.class.getDeclaredField("databaseStartFailed");
        failed.setAccessible(true);
        failed.setBoolean(monitor, true);
        Exchange unhealthy = new Exchange("GET", "/healthz");
        monitor.new HealthApiHandler().handle(unhealthy);
        assertEquals(503, unhealthy.status);
        assertTrue(unhealthy.body().contains("database_start_failed"));
    }

    @Test
    void anExceptionInOnePollDoesNotCancelTheSchedule() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch enough = new CountDownLatch(4);
        HumidityMonitor monitor = new HumidityMonitor("unused", "unused") {
            @Override
            void runPollCycle() {
                int run = runs.incrementAndGet();
                enough.countDown();
                if (run == 1) throw new IllegalStateException("boom");
                if (run == 2) throw new AssertionError("boom");
            }
        };
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            scheduler.scheduleWithFixedDelay(monitor::pollAndStore, 0, 10, TimeUnit.MILLISECONDS);
            assertTrue(enough.await(5, TimeUnit.SECONDS), "schedule died after " + runs.get() + " runs");
            assertTrue((long) field(monitor, "lastPollAttemptMillis") > 0);
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static Object field(HumidityMonitor monitor, String name) throws Exception {
        java.lang.reflect.Field field = HumidityMonitor.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(monitor);
    }

    private static final class Exchange extends com.sun.net.httpserver.HttpExchange {
        private final String method;
        private final java.net.URI uri;
        private final com.sun.net.httpserver.Headers headers = new com.sun.net.httpserver.Headers();
        private final java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        private int status;

        Exchange(String method, String target) {
            this.method = method;
            this.uri = java.net.URI.create(target);
        }

        String body() {
            return body.toString(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override public com.sun.net.httpserver.Headers getRequestHeaders() { return headers; }
        @Override public com.sun.net.httpserver.Headers getResponseHeaders() { return headers; }
        @Override public java.net.URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public com.sun.net.httpserver.HttpContext getHttpContext() { return null; }
        @Override public void close() {}
        @Override public java.io.InputStream getRequestBody() { return java.io.InputStream.nullInputStream(); }
        @Override public java.io.OutputStream getResponseBody() { return body; }
        @Override public void sendResponseHeaders(int code, long length) { status = code; }
        @Override public java.net.InetSocketAddress getRemoteAddress() { return null; }
        @Override public int getResponseCode() { return status; }
        @Override public java.net.InetSocketAddress getLocalAddress() { return null; }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(java.io.InputStream input, java.io.OutputStream output) {}
        @Override public com.sun.net.httpserver.HttpPrincipal getPrincipal() { return null; }
    }
}
