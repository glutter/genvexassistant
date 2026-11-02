import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** The guard without a host list (native and local default) and the stricter Origin rule. */
class RequestGuardSameOriginTest {
    private static boolean passes(Set<String> allowed, HttpHardeningTest.Exchange exchange) throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        new RequestGuard(allowed).doFilter(exchange, new Filter.Chain(List.of(), ex -> reached.set(true)));
        return reached.get();
    }

    private static HttpHardeningTest.Exchange post(String host) {
        return new HttpHardeningTest.Exchange("POST", "/api/fan/udluftning", new byte[0]).header("Host", host);
    }

    @Test
    void withoutAHostListAnyHostIsServedButAForeignPageCannotWrite() throws Exception {
        assertTrue(passes(Set.of(), new HttpHardeningTest.Exchange("GET", "/api/live", new byte[0]).header("Host", "anything")));
        assertTrue(passes(Set.of(), post("127.0.0.1:3100")));

        HttpHardeningTest.Exchange foreign = post("127.0.0.1:3100").header("Origin", "https://evil.example");
        assertFalse(passes(Set.of(), foreign));
        assertEquals(403, foreign.status);
    }

    @Test
    void aLoopbackPageOnAnotherPortIsNotTheSameOrigin() throws Exception {
        for (String origin : new String[] {"http://localhost:9999", "http://127.0.0.1:3090",
                "http://127.0.0.1", "http://localhost:3100"}) {
            HttpHardeningTest.Exchange exchange = post("127.0.0.1:3100").header("Origin", origin);
            assertFalse(passes(Set.of(), exchange), origin);
            assertEquals(403, exchange.status, origin);
        }
    }

    @Test
    void thePageThisServerServedMayWrite() throws Exception {
        assertTrue(passes(Set.of(), post("127.0.0.1:3100").header("Origin", "http://127.0.0.1:3100")));
        assertTrue(passes(Set.of(), post("localhost:8080").header("Origin", "http://localhost:8080")));
        assertTrue(passes(Set.of(), post("[::1]:3100").header("Origin", "http://[::1]:3100")));
        assertTrue(passes(Set.of(), post("Genvex.Example.org").header("Origin", "https://genvex.example.org")));
        assertTrue(passes(Set.of(), post("genvex.example.org:8443").header("Origin", "https://genvex.example.org:8443")));
        assertTrue(passes(Set.of(), post("127.0.0.1:3100").header("Sec-Fetch-Site", "same-origin")));
    }

    @Test
    void aBrowserThatCallsTheRequestCrossSiteIsRefusedWhateverTheOrigin() throws Exception {
        for (String site : new String[] {"cross-site", "same-site", "Cross-Site"}) {
            HttpHardeningTest.Exchange exchange = post("127.0.0.1:3100").header("Sec-Fetch-Site", site);
            assertFalse(passes(Set.of(), exchange), site);
            assertEquals(403, exchange.status);
        }
    }

    @Test
    void anOpaqueOrMalformedOriginIsRefused() throws Exception {
        for (String origin : new String[] {"null", "", "not a uri", "ftp://127.0.0.1:3100", "http://127.0.0.1:3100@evil.example"}) {
            assertFalse(passes(Set.of(), post("127.0.0.1:3100").header("Origin", origin)), origin);
        }
    }

    @Test
    void theHostListStillAppliesFirst() throws Exception {
        Set<String> allowed = Set.of("genvex.example.org");
        HttpHardeningTest.Exchange foreign = new HttpHardeningTest.Exchange("GET", "/healthz", new byte[0]).header("Host", "evil.example");
        assertFalse(passes(allowed, foreign));
        assertEquals(421, foreign.status);
        assertTrue(passes(allowed, post("genvex.example.org").header("Origin", "https://genvex.example.org")));
    }

    @Test
    void nativeRefusesAWildcardBindUnlessExplicitlyAllowed() {
        Map<String, String> env = Map.of("GENVEX_HOSTING", "native", "GENVEX_DB_PATH", "/tmp/g.db", "GENVEX_WEB_BIND", "0.0.0.0");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> HostingConfig.fromEnvironment(env));
        assertTrue(refused.getMessage().contains("GENVEX_ALLOW_REMOTE_BIND"), refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> HostingConfig.fromEnvironment(
                Map.of("GENVEX_HOSTING", "native", "GENVEX_DB_PATH", "/tmp/g.db", "GENVEX_WEB_BIND", "192.168.50.233")));

        assertEquals("0.0.0.0", HostingConfig.fromEnvironment(Map.of("GENVEX_HOSTING", "native", "GENVEX_DB_PATH", "/tmp/g.db",
                "GENVEX_WEB_BIND", "0.0.0.0", "GENVEX_ALLOW_REMOTE_BIND", "true")).webBind());
        for (String loopback : new String[] {"127.0.0.1", "127.1.2.3", "localhost", "::1"}) {
            assertEquals(loopback, HostingConfig.fromEnvironment(Map.of("GENVEX_HOSTING", "native",
                    "GENVEX_DB_PATH", "/tmp/g.db", "GENVEX_WEB_BIND", loopback)).webBind());
        }
    }

    @Test
    void localAndAddonKeepTheirWildcardBind() {
        assertEquals("0.0.0.0", HostingConfig.fromEnvironment(Map.of()).webBind());
        assertEquals("0.0.0.0", HostingConfig.fromEnvironment(Map.of("SUPERVISOR_TOKEN", "t")).webBind());
    }

    @Test
    void aDatabasePathCannotCarryJdbcOptions() {
        assertThrows(IllegalArgumentException.class, () -> HostingConfig.fromEnvironment(
                Map.of("GENVEX_HOSTING", "native", "GENVEX_DB_PATH", "/var/lib/g/genvex.db?journal_mode=OFF")));
        assertThrows(IllegalArgumentException.class, () -> HostingConfig.fromEnvironment(
                Map.of("GENVEX_HOSTING", "local", "GENVEX_DB_PATH", "x.db?mode=ro")));
    }

    @Test
    void anEmptySupervisorTokenStillSelectsTheAddonWithItsHomeAssistantEndpoint() {
        HostingConfig config = HostingConfig.fromEnvironment(Map.of("SUPERVISOR_TOKEN", ""));

        assertEquals(HostingConfig.Profile.ADDON, config.profile());
        assertEquals(new HostingConfig.HaEndpoint("http://supervisor/core", ""), config.homeAssistant());
    }
}
