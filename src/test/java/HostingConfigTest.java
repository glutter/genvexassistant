import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HostingConfigTest {
    private static HostingConfig from(Map<String, String> env) {
        return HostingConfig.fromEnvironment(env);
    }

    /** The regression test for the add-on: a Supervisor token and nothing else must behave exactly as v1.82. */
    @Test
    void supervisorTokenAloneKeepsTheAddonBehaviourOfV182() {
        HostingConfig config = from(Map.of("SUPERVISOR_TOKEN", "t0ken"));

        assertEquals(HostingConfig.Profile.ADDON, config.profile());
        assertEquals("/data/genvex.db", config.dbPath());
        assertEquals("0.0.0.0", config.webBind());
        assertEquals(8081, config.webPort());
        assertEquals(new HostingConfig.HaEndpoint("http://supervisor/core", "t0ken"), config.homeAssistant());
        assertFalse(config.stopOnStartupFailure());
        assertEquals(new InetSocketAddress(8081), config.webAddress());
    }

    @Test
    void addonIgnoresTheNativeOverrides() {
        HostingConfig config = from(Map.of("SUPERVISOR_TOKEN", "t0ken", "GENVEX_DB_PATH", "/tmp/x.db",
                "GENVEX_WEB_BIND", "127.0.0.1", "GENVEX_WEB_PORT", "3100"));

        assertEquals("/data/genvex.db", config.dbPath());
        assertEquals("0.0.0.0", config.webBind());
        assertEquals(8081, config.webPort());
    }

    @Test
    void emptyEnvironmentIsTheLocalDevelopmentProfile() {
        HostingConfig config = from(Map.of());

        assertEquals(HostingConfig.Profile.LOCAL, config.profile());
        assertEquals("genvex.db", config.dbPath());
        assertEquals("0.0.0.0", config.webBind());
        assertEquals(8081, config.webPort());
        assertNull(config.homeAssistant());
        assertFalse(config.stopOnStartupFailure());
    }

    @Test
    void nativeBindsLoopbackAndStopsOnStartupFailure() {
        HostingConfig config = from(Map.of("GENVEX_HOSTING", "native", "GENVEX_DB_PATH", "/var/lib/g/genvex.db",
                "GENVEX_WEB_PORT", "3100"));

        assertEquals(HostingConfig.Profile.NATIVE, config.profile());
        assertEquals("127.0.0.1", config.webBind());
        assertEquals(3100, config.webPort());
        assertEquals("/var/lib/g/genvex.db", config.dbPath());
        assertTrue(config.stopOnStartupFailure());
        assertEquals(new InetSocketAddress("127.0.0.1", 3100), config.webAddress());
    }

    @Test
    void nativeDefaultsToTheAddonPortAndDbFromTheStateDirectory() {
        HostingConfig config = from(Map.of("GENVEX_HOSTING", "native", "STATE_DIRECTORY", "/var/lib/genvexassistant"));

        assertEquals(8081, config.webPort());
        assertEquals("/var/lib/genvexassistant/genvex.db", config.dbPath());
    }

    @Test
    void nativeUsesTheFirstOfSeveralStateDirectories() {
        HostingConfig config = from(Map.of("GENVEX_HOSTING", "native", "STATE_DIRECTORY", "/var/lib/a/:/var/lib/b"));

        assertEquals("/var/lib/a/genvex.db", config.dbPath());
    }

    @Test
    void explicitDbPathWinsOverTheStateDirectory() {
        HostingConfig config = from(Map.of("GENVEX_HOSTING", "native", "GENVEX_DB_PATH", "/data/mine.db",
                "STATE_DIRECTORY", "/var/lib/genvexassistant"));

        assertEquals("/data/mine.db", config.dbPath());
    }

    @Test
    void nativeWithoutAnyDatabaseLocationIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> from(Map.of("GENVEX_HOSTING", "native")));

        assertTrue(error.getMessage().contains("GENVEX_DB_PATH"));
    }

    @Test
    void nativeNeverTalksToHomeAssistantEvenWithATokenPresent() {
        HostingConfig config = from(Map.of("GENVEX_HOSTING", "native", "SUPERVISOR_TOKEN", "t0ken",
                "GENVEX_DB_PATH", "/tmp/g.db"));

        assertNull(config.homeAssistant());
    }

    @Test
    void explicitHostingWinsOverTheSupervisorToken() {
        assertEquals(HostingConfig.Profile.LOCAL,
                from(Map.of("GENVEX_HOSTING", "local", "SUPERVISOR_TOKEN", "t0ken")).profile());
        assertEquals(HostingConfig.Profile.ADDON, from(Map.of("GENVEX_HOSTING", " ADDON ")).profile());
    }

    @Test
    void addonWithoutATokenHasNoHomeAssistantEndpoint() {
        assertNull(from(Map.of("GENVEX_HOSTING", "addon")).homeAssistant());
    }

    @Test
    void blankHostingFallsBackToAutoDetection() {
        assertEquals(HostingConfig.Profile.LOCAL, from(Map.of("GENVEX_HOSTING", "  ")).profile());
        assertEquals(HostingConfig.Profile.ADDON,
                from(Map.of("GENVEX_HOSTING", "", "SUPERVISOR_TOKEN", "t")).profile());
    }

    @Test
    void localHonoursTheOverrides() {
        HostingConfig config = from(Map.of("GENVEX_HOSTING", "local", "GENVEX_DB_PATH", "/tmp/dev.db",
                "GENVEX_WEB_BIND", "127.0.0.1", "GENVEX_WEB_PORT", "4000"));

        assertEquals("/tmp/dev.db", config.dbPath());
        assertEquals("127.0.0.1", config.webBind());
        assertEquals(4000, config.webPort());
    }

    @Test
    void rejectsUnknownProfilesAndBadPorts() {
        assertThrows(IllegalArgumentException.class, () -> from(Map.of("GENVEX_HOSTING", "docker")));
        for (String port : new String[] {"0", "65536", "-1", "http", "80.5"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> from(Map.of("GENVEX_HOSTING", "local", "GENVEX_WEB_PORT", port)), port);
        }
    }

    @Test
    void describesTheProfileWithoutLeakingTheToken() {
        HostingConfig config = from(Map.of("SUPERVISOR_TOKEN", "s3cret"));

        assertEquals("Hosting profile: addon, db=/data/genvex.db, web=0.0.0.0:8081, home-assistant=on",
                config.describe());
        assertFalse(config.toString().contains("s3cret"));
    }
}
