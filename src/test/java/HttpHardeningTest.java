import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Headers;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/** Limits and headers added after the security and robustness review. Tests run in the local profile. */
class HttpHardeningTest {
    private static final class Fake extends HumidityMonitor {
        Fake() {
            super("unused", "unused");
        }

        @Override
        void setFanSpeedImmediately(int speed) {
            // No device: only the request handling is under test.
        }
    }

    @Test
    void anOversizedBodyIsRefusedBeforeItIsReadIntoTheHeap() throws Exception {
        HumidityMonitor monitor = new Fake();
        byte[] big = new byte[9 * 1024];
        java.util.Arrays.fill(big, (byte) ' ');

        Map<String, com.sun.net.httpserver.HttpHandler> handlers = Map.of(
                "/api/fan/udluftning", monitor.new UdluftningApiHandler(),
                "/api/fan/static", monitor.new StaticRpmApiHandler(),
                "/api/system/mode", monitor.new SystemModeHandler());
        for (Map.Entry<String, com.sun.net.httpserver.HttpHandler> route : handlers.entrySet()) {
            Exchange exchange = new Exchange("POST", route.getKey(), big);
            route.getValue().handle(exchange);
            assertEquals(413, exchange.status, route.getKey());
        }
    }

    @Test
    void aSmallBodyIsStillParsedAsBefore() throws Exception {
        HumidityMonitor monitor = new Fake();

        Exchange bad = new Exchange("POST", "/api/fan/udluftning", "{\"level\":9,\"duration_minutes\":5}".getBytes());
        monitor.new UdluftningApiHandler().handle(bad);
        assertEquals(400, bad.status);

        Exchange good = new Exchange("POST", "/api/fan/udluftning", "{\"level\":2,\"duration_minutes\":10}".getBytes());
        monitor.new UdluftningApiHandler().handle(good);
        assertEquals(200, good.status);
        assertTrue(good.body().startsWith("{\"ok\":true,\"level\":2,\"minutes\":10,\"until\":"), good.body());
    }

    @Test
    void untilInTheAnswerIsTheEndTimeTheLockedUpdateWrote() throws Exception {
        HumidityMonitor monitor = new Fake();
        Exchange exchange = new Exchange("POST", "/api/fan/udluftning", "{\"level\":1,\"duration_minutes\":3}".getBytes());
        long before = System.currentTimeMillis();

        monitor.new UdluftningApiHandler().handle(exchange);

        java.lang.reflect.Field end = HumidityMonitor.class.getDeclaredField("manualOverrideEndTime");
        end.setAccessible(true);
        long stored = end.getLong(monitor);
        assertTrue(exchange.body().endsWith("\"until\":" + stored + "}"), exchange.body() + " vs " + stored);
        assertTrue(stored >= before + 3 * 60_000L);
    }

    @Test
    void theDashboardPageIsSentGzippedOnlyToClientsThatAskAndCannotBeFramed() throws Exception {
        Exchange plain = new Exchange("GET", "/index.html", new byte[0]);
        new HumidityMonitor.StaticFileHandler().handle(plain);
        Exchange packed = new Exchange("GET", "/index.html", new byte[0]).header("Accept-Encoding", "gzip, br");
        new HumidityMonitor.StaticFileHandler().handle(packed);

        assertEquals(200, plain.status);
        assertNull(plain.responseHeaders.getFirst("Content-Encoding"));
        assertEquals("DENY", plain.responseHeaders.getFirst("X-Frame-Options"));
        assertEquals("frame-ancestors 'none'", plain.responseHeaders.getFirst("Content-Security-Policy"));
        assertEquals("nosniff", plain.responseHeaders.getFirst("X-Content-Type-Options"));
        assertEquals("gzip", packed.responseHeaders.getFirst("Content-Encoding"));
        assertEquals("Accept-Encoding", packed.responseHeaders.getFirst("Vary"));
        assertTrue(packed.out.size() < plain.out.size() / 3);
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(packed.out.toByteArray()))) {
            assertArrayEquals(plain.out.toByteArray(), in.readAllBytes());
        }
    }

    @Test
    void parsesAcceptEncoding() {
        assertTrue(HumidityMonitor.acceptsGzip("gzip"));
        assertTrue(HumidityMonitor.acceptsGzip("gzip, deflate, br"));
        assertTrue(HumidityMonitor.acceptsGzip("br, GZIP;q=0.5"));
        assertTrue(HumidityMonitor.acceptsGzip("*"));
        assertFalse(HumidityMonitor.acceptsGzip(null));
        assertFalse(HumidityMonitor.acceptsGzip(""));
        assertFalse(HumidityMonitor.acceptsGzip("identity"));
        assertFalse(HumidityMonitor.acceptsGzip("gzip;q=0"));
        assertFalse(HumidityMonitor.acceptsGzip("*;q=0"));
        assertFalse(HumidityMonitor.acceptsGzip("gzip;q=x"));
    }

    @Test
    void historyChunksWriteTheHeadersOnceAndRoundTripThroughGzip() throws Exception {
        Exchange client = new Exchange("GET", "/api/history", new byte[0]).header("Accept-Encoding", "gzip");
        StringBuilder json = new StringBuilder("[{\"a\":1},");
        OutputStream out = HumidityMonitor.HistoryApiHandler.flushChunk(client, null, json);
        assertEquals(0, json.length());
        assertEquals(1, client.sendCount);
        json.append("{\"b\":2}]");
        out = HumidityMonitor.HistoryApiHandler.flushChunk(client, out, json);
        out.close();

        assertEquals(1, client.sendCount);
        assertEquals(0L, client.length);
        assertEquals("gzip", client.responseHeaders.getFirst("Content-Encoding"));
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(client.out.toByteArray()))) {
            assertEquals("[{\"a\":1},{\"b\":2}]", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void historyChunksAreSentPlainWhenTheClientDoesNotAskForGzip() throws Exception {
        Exchange client = new Exchange("GET", "/api/history", new byte[0]);
        StringBuilder json = new StringBuilder("[]");

        OutputStream out = HumidityMonitor.HistoryApiHandler.flushChunk(client, null, json);
        out.close();

        assertNull(client.responseHeaders.getFirst("Content-Encoding"));
        assertEquals("[]", client.body());
        assertNotNull(out);
    }

    static final class Exchange extends com.sun.net.httpserver.HttpExchange {
        private final String method;
        private final URI uri;
        private final byte[] request;
        final Headers requestHeaders = new Headers();
        final Headers responseHeaders = new Headers();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int status;
        int sendCount;
        long length = -1;

        Exchange(String method, String path, byte[] body) {
            this.method = method;
            this.uri = URI.create(path);
            this.request = body;
        }

        Exchange header(String name, String value) {
            requestHeaders.add(name, value);
            return this;
        }

        String body() {
            return out.toString(StandardCharsets.UTF_8);
        }

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public com.sun.net.httpserver.HttpContext getHttpContext() { return null; }
        @Override public void close() {}
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(request); }
        @Override public OutputStream getResponseBody() { return out; }
        @Override public void sendResponseHeaders(int code, long len) { status = code; length = len; sendCount++; }
        @Override public java.net.InetSocketAddress getRemoteAddress() { return null; }
        @Override public int getResponseCode() { return status; }
        @Override public java.net.InetSocketAddress getLocalAddress() { return null; }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(InputStream input, OutputStream output) {}
        @Override public com.sun.net.httpserver.HttpPrincipal getPrincipal() { return null; }
    }
}
