import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * The answer to {@code GET /healthz}: is this process fit to keep running? Deliberately independent of whether the
 * ventilation unit answers, otherwise a deploy health check would roll back a healthy build while the unit is down.
 * The unit's state is reported separately in {@code device}.
 */
record HealthReport(List<String> problems, String device, long lastPollAgeSeconds, long uptimeSeconds,
        String version, String profile) {

    static final int DATABASE_ERROR_LIMIT = 5;
    static final String VERSION_RESOURCE = "/META-INF/maven/com.genvex/genvex-integration/pom.properties";

    static HealthReport evaluate(long nowMillis, long startedAtMillis, long lastPollAttemptMillis,
            long maxPollAgeMillis, boolean databaseStartFailed, int databaseErrors, boolean shuttingDown,
            String device, String version, String profile) {
        List<String> problems = new ArrayList<>();
        if (shuttingDown) problems.add("shutting_down");
        if (databaseStartFailed) problems.add("database_start_failed");
        if (databaseErrors >= DATABASE_ERROR_LIMIT) problems.add("database_errors");
        // Before the first poll the process start is the reference, so a slow first connection is not a stall.
        long reference = lastPollAttemptMillis > 0 ? lastPollAttemptMillis : startedAtMillis;
        if (nowMillis - reference > maxPollAgeMillis) problems.add("poll_loop_stalled");
        long pollAge = lastPollAttemptMillis > 0 ? Math.max(0, (nowMillis - lastPollAttemptMillis) / 1000) : -1;
        return new HealthReport(List.copyOf(problems), device, pollAge,
                Math.max(0, (nowMillis - startedAtMillis) / 1000), version, profile);
    }

    /** The poll loop must have tried within this window, whatever the poll interval is. */
    static long maxPollAgeMillis(int pollIntervalSeconds) {
        return Math.max(300L, 10L * pollIntervalSeconds) * 1000L;
    }

    /** ok / stale / failed / awaiting: what the last poll says about the unit, never about this process. */
    static String deviceStatus(boolean lastPollFailed, long sampleAgeMillis, boolean hasSample, long staleAfterMillis) {
        if (lastPollFailed) return "failed";
        if (!hasSample) return "awaiting";
        return sampleAgeMillis <= staleAfterMillis ? "ok" : "stale";
    }

    boolean healthy(boolean requireDevice) {
        return problems.isEmpty() && (!requireDevice || "ok".equals(device));
    }

    String json(boolean requireDevice) {
        List<String> shown = new ArrayList<>(problems);
        if (requireDevice && !"ok".equals(device)) shown.add("device_not_ok");
        StringBuilder problemsJson = new StringBuilder("[");
        for (int index = 0; index < shown.size(); index++) {
            if (index > 0) problemsJson.append(',');
            problemsJson.append(HumidityMonitor.jsonString(shown.get(index)));
        }
        problemsJson.append(']');
        return String.format(Locale.ROOT,
                "{\"status\":%s,\"version\":%s,\"profile\":%s,\"uptime_seconds\":%d,\"last_poll_age_seconds\":%s,"
                        + "\"device\":%s,\"problems\":%s}",
                HumidityMonitor.jsonString(shown.isEmpty() ? "ok" : "unavailable"),
                HumidityMonitor.jsonString(version), HumidityMonitor.jsonString(profile), uptimeSeconds,
                lastPollAgeSeconds < 0 ? "null" : Long.toString(lastPollAgeSeconds),
                HumidityMonitor.jsonString(device), problemsJson);
    }

    /** Written by Maven into the jar; absent when running from target/classes, hence "unknown". */
    static String readVersion() {
        try (InputStream stream = HealthReport.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (stream == null) return "unknown";
            Properties properties = new Properties();
            properties.load(stream);
            return properties.getProperty("version", "unknown");
        } catch (IOException exception) {
            return "unknown";
        }
    }
}
