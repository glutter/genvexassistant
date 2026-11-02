import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Where the service runs and what it may talk to, resolved once from the environment.
 * The add-on profile is byte-for-byte the behaviour of v1.82; the other two exist for a systemd install
 * and for development.
 */
record HostingConfig(Profile profile, String dbPath, String webBind, int webPort, HaEndpoint homeAssistant,
        Coordinates location, Set<String> allowedHosts) {

    enum Profile {
        ADDON, NATIVE, LOCAL;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The Supervisor proxy to Home Assistant core, with the token that authorises it. */
    record HaEndpoint(String baseUrl, String token) {
        @Override
        public String toString() {
            return "HaEndpoint[" + baseUrl + "]";
        }
    }

    /** Where sunset is computed when Home Assistant cannot say; absent means the fixed cooling window is used. */
    record Coordinates(double latitude, double longitude) {}

    static final String ADDON_DB_PATH = "/data/genvex.db";
    static final String LOCAL_DB_PATH = "genvex.db";
    static final int DEFAULT_WEB_PORT = 8081;
    private static final String ANY_ADDRESS = "0.0.0.0";
    private static final String LOOPBACK = "127.0.0.1";
    private static final String SUPERVISOR_CORE_URL = "http://supervisor/core";

    static HostingConfig fromEnvironment(Map<String, String> env) {
        boolean supervisorPresent = env.get("SUPERVISOR_TOKEN") != null;
        Profile profile = selectProfile(env.get("GENVEX_HOSTING"), supervisorPresent);

        // The add-on's db path, port and bind are part of its ingress contract, so the overrides never apply there.
        String dbPath = switch (profile) {
            case ADDON -> ADDON_DB_PATH;
            case LOCAL -> valueOrDefault(env.get("GENVEX_DB_PATH"), LOCAL_DB_PATH);
            case NATIVE -> nativeDbPath(env);
        };
        String bind = profile == Profile.ADDON ? ANY_ADDRESS
                : valueOrDefault(env.get("GENVEX_WEB_BIND"), profile == Profile.NATIVE ? LOOPBACK : ANY_ADDRESS);
        int port = profile == Profile.ADDON ? DEFAULT_WEB_PORT : parsePort(env.get("GENVEX_WEB_PORT"));
        HaEndpoint homeAssistant = profile == Profile.ADDON && supervisorPresent
                ? new HaEndpoint(SUPERVISOR_CORE_URL, env.get("SUPERVISOR_TOKEN")) : null;

        return new HostingConfig(profile, dbPath, bind, port, homeAssistant,
                parseLocation(env.get("GENVEX_LATITUDE"), env.get("GENVEX_LONGITUDE")),
                parseAllowedHosts(env.get("GENVEX_ALLOWED_HOSTS")));
    }

    /** Comma-separated host names; empty or unset switches the Host/Origin check off. */
    private static Set<String> parseAllowedHosts(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> hosts = new LinkedHashSet<>();
        for (String entry : value.split(",")) {
            if (entry.isBlank()) {
                continue;
            }
            String host = entry.contains("/") ? null : RequestGuard.hostOf(entry);
            if (host == null) {
                throw new IllegalArgumentException("GENVEX_ALLOWED_HOSTS must be comma-separated host names");
            }
            hosts.add(host);
        }
        return Set.copyOf(hosts);
    }

    private static Coordinates parseLocation(String latitude, String longitude) {
        boolean hasLatitude = latitude != null && !latitude.isBlank();
        boolean hasLongitude = longitude != null && !longitude.isBlank();
        if (!hasLatitude && !hasLongitude) {
            return null;
        }
        if (hasLatitude != hasLongitude) {
            throw new IllegalArgumentException("GENVEX_LATITUDE and GENVEX_LONGITUDE must be set together");
        }
        return new Coordinates(parseDegrees("GENVEX_LATITUDE", latitude, 90), parseDegrees("GENVEX_LONGITUDE", longitude, 180));
    }

    private static double parseDegrees(String name, String value, double limit) {
        try {
            double degrees = Double.parseDouble(value.trim());
            if (Double.isFinite(degrees) && Math.abs(degrees) <= limit) {
                return degrees;
            }
        } catch (NumberFormatException ignored) {
            // Falls through to the shared message below.
        }
        throw new IllegalArgumentException(name + " must be a number from -" + (int) limit + " to " + (int) limit);
    }

    static Profile selectProfile(String requested, boolean supervisorPresent) {
        if (requested != null && !requested.isBlank()) {
            return switch (requested.trim().toLowerCase(Locale.ROOT)) {
                case "addon" -> Profile.ADDON;
                case "native" -> Profile.NATIVE;
                case "local" -> Profile.LOCAL;
                default -> throw new IllegalArgumentException("GENVEX_HOSTING must be addon, native or local");
            };
        }
        return supervisorPresent ? Profile.ADDON : Profile.LOCAL;
    }

    private static String nativeDbPath(Map<String, String> env) {
        String explicit = env.get("GENVEX_DB_PATH");
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim();
        }
        // systemd may list several state directories separated by ':'; the first one is ours.
        String stateDirectory = env.get("STATE_DIRECTORY");
        if (stateDirectory != null && !stateDirectory.isBlank()) {
            String first = stateDirectory.trim().split(":", 2)[0];
            return (first.endsWith("/") ? first : first + "/") + "genvex.db";
        }
        throw new IllegalArgumentException("Native hosting needs GENVEX_DB_PATH or STATE_DIRECTORY");
    }

    private static int parsePort(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_WEB_PORT;
        }
        try {
            int port = Integer.parseInt(value.trim());
            if (port >= 1 && port <= 65_535) {
                return port;
            }
        } catch (NumberFormatException ignored) {
            // Falls through to the shared message below.
        }
        throw new IllegalArgumentException("GENVEX_WEB_PORT must be an integer from 1 to 65535");
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** Native hosting is unattended: a half-started instance would still drive the fan, so startup errors are fatal. */
    boolean stopOnStartupFailure() {
        return profile == Profile.NATIVE;
    }

    InetSocketAddress webAddress() {
        // Wildcard through the port-only constructor keeps the add-on's bind identical to v1.82.
        return ANY_ADDRESS.equals(webBind) ? new InetSocketAddress(webPort) : new InetSocketAddress(webBind, webPort);
    }

    String describe() {
        return "Hosting profile: " + profile.label() + ", db=" + dbPath + ", web=" + webBind + ":" + webPort
                + ", home-assistant=" + (homeAssistant == null ? "off" : "on") + ", sun=" + sunDescription();
    }

    /** Sources of the sunset time in priority order; the fixed cooling window is the last resort. */
    String sunDescription() {
        List<String> sources = new ArrayList<>();
        if (homeAssistant != null) sources.add("home-assistant");
        if (location != null) {
            sources.add(String.format(Locale.ROOT, "local(%s,%s)", degrees(location.latitude()), degrees(location.longitude())));
        }
        return sources.isEmpty() ? "window" : String.join("+", sources);
    }

    private static String degrees(double value) {
        return new java.math.BigDecimal(value).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
