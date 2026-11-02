import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RequestGuardTest {
    private static final Set<String> ALLOWED = Set.of("genvex.example.org");

    /** Runs the guard and reports whether the request reached the handler behind it. */
    private static boolean passes(Exchange exchange) throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        Filter.Chain chain = new Filter.Chain(List.of(), ex -> reached.set(true));
        new RequestGuard(ALLOWED).doFilter(exchange, chain);
        return reached.get();
    }

    @Test
    void allowsAConfiguredHostWithOrWithoutAPort() throws Exception {
        assertTrue(passes(new Exchange("GET", "/api/live").header("Host", "genvex.example.org")));
        assertTrue(passes(new Exchange("GET", "/api/live").header("Host", "GENVEX.example.org:443")));
    }

    @Test
    void alwaysAllowsLoopbackSoHealthChecksAndSshTunnelsKeepWorking() throws Exception {
        assertTrue(passes(new Exchange("GET", "/healthz").header("Host", "127.0.0.1:3100")));
        assertTrue(passes(new Exchange("GET", "/").header("Host", "localhost:3100")));
        assertTrue(passes(new Exchange("GET", "/").header("Host", "[::1]:3100")));
    }

    @Test
    void refusesAForeignOrMissingHostWith421() throws Exception {
        Exchange foreign = new Exchange("GET", "/api/live").header("Host", "evil.example.net");
        assertFalse(passes(foreign));
        assertEquals(421, foreign.status);

        Exchange missing = new Exchange("GET", "/api/live");
        assertFalse(passes(missing));
        assertEquals(421, missing.status);
    }

    @Test
    void refusesAStateChangingRequestFromAForeignOriginWith403() throws Exception {
        Exchange foreign = new Exchange("POST", "/api/fan/udluftning")
                .header("Host", "genvex.example.org").header("Origin", "https://evil.example.net");
        assertFalse(passes(foreign));
        assertEquals(403, foreign.status);

        Exchange opaque = new Exchange("POST", "/api/fan/udluftning")
                .header("Host", "genvex.example.org").header("Origin", "null");
        assertFalse(passes(opaque));
        assertEquals(403, opaque.status);
    }

    @Test
    void allowsSameSiteOriginsAndRequestsWithoutAnOrigin() throws Exception {
        assertTrue(passes(new Exchange("POST", "/api/fan/udluftning")
                .header("Host", "genvex.example.org").header("Origin", "https://genvex.example.org")));
        assertTrue(passes(new Exchange("POST", "/api/fan/udluftning").header("Host", "genvex.example.org")));
        // A read-only request is not checked for Origin: browsers send it on cross-origin fetches too.
        assertTrue(passes(new Exchange("GET", "/api/live")
                .header("Host", "genvex.example.org").header("Origin", "https://evil.example.net")));
    }

    @Test
    void parsesHostAndOriginHeaders() {
        assertEquals("example.org", RequestGuard.hostOf("Example.ORG:8080"));
        assertEquals("::1", RequestGuard.hostOf("[::1]:3100"));
        assertNull(RequestGuard.hostOf(" "));
        assertNull(RequestGuard.hostOf("[::1"));
        assertEquals("example.org", RequestGuard.hostOfOrigin("https://example.org:8443"));
        assertEquals("::1", RequestGuard.hostOfOrigin("http://[::1]:3100"));
        assertNull(RequestGuard.hostOfOrigin("null"));
        assertNull(RequestGuard.hostOfOrigin("not a uri"));
    }

    @Test
    void staticHandlerServesOnlyTheDashboardPage() throws Exception {
        for (String path : new String[] {"/", "/index.html"}) {
            Exchange page = new Exchange("GET", path);
            new HumidityMonitor.StaticFileHandler().handle(page);
            assertEquals(200, page.status, path);
            assertTrue(page.body().contains("<html"), path);
        }
        for (String path : new String[] {"/HumidityMonitor.class", "/pom.xml", "/META-INF/MANIFEST.MF",
                "/org/sqlite/native/Linux/x86_64/libsqlitejdbc.so", "/../pom.xml", "/index.html.bak", "/missing"}) {
            Exchange hidden = new Exchange("GET", path);
            new HumidityMonitor.StaticFileHandler().handle(hidden);
            assertEquals(404, hidden.status, path);
        }
    }

    private static final class Exchange extends com.sun.net.httpserver.HttpExchange {
        private final String method;
        private final java.net.URI uri;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        private int status;

        Exchange(String method, String path) {
            this.method = method;
            this.uri = java.net.URI.create(path);
        }

        Exchange header(String name, String value) {
            requestHeaders.add(name, value);
            return this;
        }

        String body() {
            return body.toString(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
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
