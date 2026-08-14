import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.URL;

public class HumidityMonitor {

    // Database Configuration (SQLite)
    // Use /data/genvex.db if running in Home Assistant (persistent), otherwise local file
    private static final String DB_PATH = System.getenv().containsKey("SUPERVISOR_TOKEN") ? "/data/genvex.db" : "genvex.db";
    private static final String DB_URL = "jdbc:sqlite:" + DB_PATH;
    
    private static final int WEB_PORT = 8081; // Different from GenvexServer 8080

    private final GenvexClient client;
    private final Object clientLock = new Object();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final ExecutorService homeAssistantPublisher = Executors.newSingleThreadExecutor();
    private final AtomicReference<PollResult> pendingHomeAssistantResult = new AtomicReference<>();
    private final AtomicBoolean homeAssistantPublishRunning = new AtomicBoolean();
    private final String sessionId = java.util.UUID.randomUUID().toString().substring(0, 6);

    // Configuration
    private static final int POLL_INTERVAL = Integer.parseInt(System.getenv().getOrDefault("POLL_INTERVAL", "30"));
    private volatile boolean monitorOnly = Boolean.parseBoolean(System.getenv().getOrDefault("MONITOR_ONLY", "false"));

    // Boost Configuration
    private static final boolean BOOST_ENABLED = Boolean.parseBoolean(System.getenv().getOrDefault("BOOST_ENABLED", "true"));
    private static final int HUMIDITY_RISE_THRESHOLD = Integer.parseInt(System.getenv().getOrDefault("HUMIDITY_RISE_THRESHOLD", "4")); // % rise per poll
    private static final int BOOST_SPEED = Integer.parseInt(System.getenv().getOrDefault("BOOST_SPEED", "3"));
    private static final int NORMAL_SPEED = Integer.parseInt(System.getenv().getOrDefault("NORMAL_SPEED", "1"));
    private static final long BOOST_DURATION_MS = Integer.parseInt(System.getenv().getOrDefault("BOOST_DURATION_MINUTES", "15")) * 60 * 1000L;
    private static final int HUMIDITY_BASELINE_MINUTES = Integer.parseInt(System.getenv().getOrDefault("HUMIDITY_BASELINE_MINUTES", "30"));
    private static final int HUMIDITY_RECOVERY_TOLERANCE = Integer.parseInt(System.getenv().getOrDefault("HUMIDITY_RECOVERY_TOLERANCE", "1"));

    // General Control Configuration
    private static final int HUMIDITY_VERY_HIGH_THRESHOLD = Integer.parseInt(System.getenv().getOrDefault("HUMIDITY_VERY_HIGH_THRESHOLD", "80"));
    private static final int HUMIDITY_HIGH_THRESHOLD = Integer.parseInt(System.getenv().getOrDefault("HUMIDITY_HIGH_THRESHOLD", "65"));
    private static final int HUMIDITY_LOW_THRESHOLD = Integer.parseInt(System.getenv().getOrDefault("HUMIDITY_LOW_THRESHOLD", "30"));
    private static final LocalTime NIGHT_START = LocalTime.parse(System.getenv().getOrDefault("NIGHT_START", "22:00"));
    private static final LocalTime NIGHT_END = LocalTime.parse(System.getenv().getOrDefault("NIGHT_END", "06:30"));

    // Evening Cooling Configuration
    private static final boolean EVENING_COOLING_ENABLED = Boolean.parseBoolean(System.getenv().getOrDefault("EVENING_COOLING_ENABLED", "true"));
    private static final double COOLING_STOP_TEMP = Double.parseDouble(System.getenv().getOrDefault(
            "COOLING_STOP_TEMP", System.getenv().getOrDefault("COOLING_TARGET_TEMP", "22.0")));
    private static final double COOLING_START_TEMP = Double.parseDouble(System.getenv().getOrDefault(
            "COOLING_START_TEMP", String.valueOf(COOLING_STOP_TEMP + 0.5)));
    private static final double COOLING_MIN_SUPPLY_TEMP = Double.parseDouble(System.getenv().getOrDefault("COOLING_MIN_SUPPLY_TEMP", "15.0"));
    private static final LocalTime COOLING_FALLBACK_START = LocalTime.parse(System.getenv().getOrDefault("COOLING_FALLBACK_START", "18:00"));
    private static final long COOLING_ESCALATION_MS = Integer.parseInt(System.getenv().getOrDefault("COOLING_ESCALATION_MINUTES", "30")) * 60 * 1000L;
    private static final double COOLING_PROGRESS_C = 0.3;
    private static final long SUN_STATE_CACHE_MS = 5 * 60 * 1000L;

    // State
    private int lastHumidity = -1;
    private long lastHumidityTime = 0;
    private double lastSupplyTemp = -1.0;
    private double lastOutsideTemp = -1.0;
    private double lastExhaustTemp = -1.0;
    private double lastExtractTemp = -1.0;
    private int lastRpm = -1;
    private int lastBypassState = -1;
    private boolean boostActive = false;
    private long boostEndTime = 0;
    private long boostMinEndTime = 0; // Minimum boost duration before allowing deactivation
    private double boostBaselineHumidity = Double.NaN;
    private boolean boostExtensionLogged = false;
    private double humidityRiseCandidateBaseline = Double.NaN;
    private int commandedFanSpeed = -1;
    private int lastObservedFanSpeed = -1;
    private int dbErrorCount = 0;
    // Manual override (Udluftning)
    private volatile boolean manualOverrideActive = false;
    private volatile long manualOverrideEndTime = 0;
    private volatile int manualOverrideSpeed = -1;
    // Static RPM Mode
    private volatile boolean staticRpmMode = false;
    private volatile int staticRpmSpeed = 2;
    private boolean eveningCoolingActive = false;
    private int eveningCoolingSpeed = 0;
    private double coolingBaselineIndoorTemp = Double.NaN;
    private long coolingBaselineTime = 0;
    private long lastSunStateCheck = 0;
    private boolean lastSunBelowHorizon = false;
    private boolean sunStateAvailable = false;

    public HumidityMonitor(String ip, String email) {
        this.client = new GenvexClient(ip, email);
    }

    public void start() {
        validateControlConfiguration(HUMIDITY_RISE_THRESHOLD, HUMIDITY_BASELINE_MINUTES,
                HUMIDITY_RECOVERY_TOLERANCE, BOOST_DURATION_MS, BOOST_SPEED, NORMAL_SPEED,
                HUMIDITY_LOW_THRESHOLD, HUMIDITY_HIGH_THRESHOLD, HUMIDITY_VERY_HIGH_THRESHOLD);

        // Initialize Database
        initializeDatabase();

        // Start Web Server
        startWebServer();

        log("Starting polling service with Session ID: " + sessionId);
        if (COOLING_START_TEMP < COOLING_STOP_TEMP) {
            logError(String.format(Locale.ROOT,
                "Evening cooling disabled: start temperature %.1fC must be at least stop temperature %.1fC.",
                COOLING_START_TEMP, COOLING_STOP_TEMP));
        }

        // Run with fixed delay to allow natural drift and prevent lock-step collisions
        scheduler.scheduleWithFixedDelay(this::pollAndStore, 0, POLL_INTERVAL, TimeUnit.SECONDS);
        
        // Run cleanup daily
        scheduler.scheduleAtFixedRate(this::cleanupOldData, 1, 24, TimeUnit.HOURS);
        
        System.out.println("Humidity Monitor started. Session ID: " + sessionId);
    }

    static void validateControlConfiguration(int riseThreshold, int baselineMinutes,
            int recoveryTolerance, long boostDurationMillis, int boostSpeed, int normalSpeed,
            int lowThreshold, int highThreshold, int veryHighThreshold) {
        if (riseThreshold <= 0 || riseThreshold > 100) {
            throw new IllegalArgumentException("HUMIDITY_RISE_THRESHOLD must be from 1 to 100");
        }
        if (baselineMinutes <= 0) {
            throw new IllegalArgumentException("HUMIDITY_BASELINE_MINUTES must be positive");
        }
        if (recoveryTolerance < 0 || recoveryTolerance > 100) {
            throw new IllegalArgumentException("HUMIDITY_RECOVERY_TOLERANCE must be from 0 to 100");
        }
        if (boostDurationMillis <= 0) {
            throw new IllegalArgumentException("BOOST_DURATION_MINUTES must be positive");
        }
        if (boostSpeed < 0 || boostSpeed > 4 || normalSpeed < 0 || normalSpeed > 4) {
            throw new IllegalArgumentException("BOOST_SPEED and NORMAL_SPEED must be from 0 to 4");
        }
        if (lowThreshold < 0 || lowThreshold >= highThreshold
                || highThreshold > veryHighThreshold || veryHighThreshold > 100) {
            throw new IllegalArgumentException("Humidity thresholds must satisfy 0 <= low < high <= very high <= 100");
        }
    }

    private void initializeDatabase() {
        String sql = "CREATE TABLE IF NOT EXISTS humidity_readings (" +
                     "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                     "humidity INTEGER, " +
                     "temp_supply REAL, " +
                     "temp_outside REAL, " +
                     "temp_exhaust REAL, " +
                     "temp_extract REAL, " +
                     "fan_rpm INTEGER, " +
                     "fan_speed_level INTEGER, " +
                     "bypass_open INTEGER)";
        
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            ensureHistoryColumns(conn);
            ensureControlStateTable(conn);
            restoreControlState(loadControlState(conn));
            saveControlState(conn, controlStateSnapshot());
            log("Database initialized at " + DB_PATH);
        } catch (SQLException e) {
            logError("Failed to initialize database: " + e.getMessage());
        }
    }

    static void ensureHistoryColumns(Connection conn) throws SQLException {
        addColumnIfMissing(conn, "temp_outside", "REAL");
        addColumnIfMissing(conn, "temp_exhaust", "REAL");
        addColumnIfMissing(conn, "temp_extract", "REAL");
        addColumnIfMissing(conn, "fan_speed_level", "INTEGER");
        addColumnIfMissing(conn, "bypass_open", "INTEGER");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_humidity_timestamp ON humidity_readings(timestamp)");
        }
    }

    static void ensureControlStateTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS control_state ("
                    + "id INTEGER PRIMARY KEY CHECK (id = 1), "
                    + "boost_active INTEGER NOT NULL DEFAULT 0, "
                    + "boost_baseline REAL, "
                    + "boost_min_end INTEGER NOT NULL DEFAULT 0, "
                    + "boost_end INTEGER NOT NULL DEFAULT 0, "
                    + "rise_candidate REAL)");
            statement.execute("INSERT OR IGNORE INTO control_state (id) VALUES (1)");
        }
    }

    static void saveControlState(Connection connection, ControlState state) throws SQLException {
        String sql = "UPDATE control_state SET boost_active = ?, boost_baseline = ?, "
                + "boost_min_end = ?, boost_end = ?, rise_candidate = ? WHERE id = 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, state.boostActive() ? 1 : 0);
            setNullableDouble(statement, 2, state.boostBaseline());
            statement.setLong(3, state.boostMinEnd());
            statement.setLong(4, state.boostEnd());
            setNullableDouble(statement, 5, state.riseCandidate());
            statement.executeUpdate();
        }
    }

    static ControlState loadControlState(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT boost_active, boost_baseline, "
                     + "boost_min_end, boost_end, rise_candidate FROM control_state WHERE id = 1")) {
            if (result.next()) {
                boolean active = result.getInt("boost_active") == 1;
                double baseline = result.getDouble("boost_baseline");
                if (result.wasNull()) baseline = Double.NaN;
                long minimumEnd = result.getLong("boost_min_end");
                long end = result.getLong("boost_end");
                double candidate = result.getDouble("rise_candidate");
                if (result.wasNull()) candidate = Double.NaN;
                return new ControlState(active, baseline, minimumEnd, end, candidate);
            }
        }
        return new ControlState(false, Double.NaN, 0, 0, Double.NaN);
    }

    static record ControlState(boolean boostActive, double boostBaseline, long boostMinEnd,
            long boostEnd, double riseCandidate) {}

    private static void addColumnIfMissing(Connection conn, String columnName, String columnType) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(humidity_readings)")) {
            while (rs.next()) {
                if (columnName.equals(rs.getString("name"))) {
                    return;
                }
            }
        }

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE humidity_readings ADD COLUMN " + columnName + " " + columnType);
        }
    }

    private void startWebServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(WEB_PORT), 0);
            server.createContext("/", new StaticFileHandler());
            server.createContext("/api/history", new HistoryApiHandler());
            server.createContext("/api/live", new LiveApiHandler());
            server.createContext("/api/fan/udluftning", new UdluftningApiHandler());
            server.createContext("/api/fan/static", new StaticRpmApiHandler());
            server.createContext("/api/system/restart", new RestartApiHandler());
            server.createContext("/api/system/mode", new SystemModeHandler());
            server.setExecutor(null);
            server.start();
            log("Web Dashboard started on port " + WEB_PORT);
        } catch (IOException e) {
            logError("Failed to start web server: " + e.getMessage());
        }
    }

    private static void sendJson(HttpExchange t, String json) throws IOException {
        byte[] bytes = json.getBytes("UTF-8");
        t.getResponseHeaders().add("Content-Type", "application/json");
        t.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = t.getResponseBody()) { os.write(bytes); }
    }

    private static String jsonTemperature(double temperature) {
        return Double.isFinite(temperature) ? String.format(Locale.ROOT, "%.1f", temperature) : "null";
    }

    private static void sendError(HttpExchange t, int code, String message) throws IOException {
        String json = "{\"error\": \"" + message + "\"}";
        byte[] bytes = json.getBytes("UTF-8");
        t.getResponseHeaders().add("Content-Type", "application/json");
        t.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = t.getResponseBody()) { os.write(bytes); }
    }

    private static String getJsonValue(String json, String key, String defaultValue) {
        try {
            // Regex to find "key": value OR "key" : value (handles booleans, numbers, strings)
            // This is a naive implementation but better than manual split logic
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*([^,}\\]]+)");
            java.util.regex.Matcher matcher = pattern.matcher(json);
            if (matcher.find()) {
                return matcher.group(1).trim().replaceAll("\"", "");
            }
        } catch (Exception e) {}
        return defaultValue;
    }

    class LiveApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            LiveSnapshot snapshot;
            synchronized (clientLock) {
                long now = System.currentTimeMillis();
                snapshot = new LiveSnapshot(lastHumidity, lastSupplyTemp, lastOutsideTemp, lastExhaustTemp,
                    lastExtractTemp, lastRpm, lastBypassState, lastObservedFanSpeed, commandedFanSpeed,
                    boostActive,
                    boostBaselineHumidity + HUMIDITY_RECOVERY_TOLERANCE,
                    boostActive && now >= boostEndTime,
                        eveningCoolingActive, eveningCoolingSpeed, staticRpmMode, staticRpmSpeed, monitorOnly,
                        manualOverrideActive && now < manualOverrideEndTime,
                        Math.max(0, (manualOverrideEndTime - now) / 1000),
                        Math.max(0, (boostEndTime - now) / 1000));
            }

            String json = String.format(Locale.ROOT,
                "{\"humidity\":%d, \"temp\":%s, \"temp_supply\":%s, \"temp_outside\":%s, \"temp_exhaust\":%s, \"temp_extract\":%s, \"rpm\":%d, \"bypass_open\":%s, \"fan_speed\":%d, \"commanded_speed\":%d, \"boost\":%b, \"boost_recovery_target\":%s, \"boost_extended\":%b, \"evening_cooling\":%b, \"evening_cooling_speed\":%d, \"static_mode\":%b, \"static_speed\":%d, \"monitor_only\":%b, \"manual_override_active\":%b, \"manual_override_secs_left\":%d, \"boost_secs_left\":%d}",
                snapshot.humidity(), jsonTemperature(snapshot.tempSupply()), jsonTemperature(snapshot.tempSupply()),
                jsonTemperature(snapshot.tempOutside()), jsonTemperature(snapshot.tempExhaust()),
                jsonTemperature(snapshot.tempExtract()), snapshot.rpm(), jsonBypassState(snapshot.bypassState()),
                snapshot.observedFanSpeed(),
                snapshot.commandedFanSpeed(), snapshot.boostActive(),
                jsonTemperature(snapshot.boostRecoveryTarget()), snapshot.boostExtended(),
                snapshot.eveningCoolingActive(),
                snapshot.eveningCoolingSpeed(), snapshot.staticMode(), snapshot.staticSpeed(), snapshot.monitorOnly(),
                snapshot.manualOverrideActive(), snapshot.manualOverrideSecsLeft(), snapshot.boostSecsLeft()
            );
            sendJson(t, json);
        }
    }

        private record LiveSnapshot(int humidity, double tempSupply, double tempOutside, double tempExhaust,
            double tempExtract, int rpm, int bypassState, int observedFanSpeed, int commandedFanSpeed,
            boolean boostActive,
            double boostRecoveryTarget, boolean boostExtended,
            boolean eveningCoolingActive, int eveningCoolingSpeed, boolean staticMode, int staticSpeed,
            boolean monitorOnly, boolean manualOverrideActive, long manualOverrideSecsLeft, long boostSecsLeft) {}

    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String path = t.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/index.html";
            }
            
            // Simple security check to prevent directory traversal
            if (path.contains("..")) {
                String response = "403 Forbidden";
                t.sendResponseHeaders(403, response.length());
                try (OutputStream os = t.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            // Load from resources
            java.io.InputStream is = getClass().getResourceAsStream(path);
            if (is == null) {
                String response = "404 Not Found";
                t.sendResponseHeaders(404, response.length());
                try (OutputStream os = t.getResponseBody()) {
                    os.write(response.getBytes());
                }
            } else {
                if (path.endsWith(".html")) {
                    t.getResponseHeaders().add("Content-Type", "text/html");
                } else if (path.endsWith(".js")) {
                    t.getResponseHeaders().add("Content-Type", "application/javascript");
                } else if (path.endsWith(".css")) {
                    t.getResponseHeaders().add("Content-Type", "text/css");
                }
                
                t.sendResponseHeaders(200, 0);
                try (OutputStream os = t.getResponseBody()) {
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = is.read(buffer)) != -1) {
                        os.write(buffer, 0, count);
                    }
                }
            }
        }
    }

    static class HistoryApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            t.getResponseHeaders().add("Content-Type", "application/json");

            String query = t.getRequestURI().getQuery();
            String range = "day"; // default
            if (query != null) {
                for (String part : query.split("&")) {
                    String[] kv = part.split("=");
                    if (kv.length == 2 && "range".equals(kv[0])) {
                        range = kv[1];
                    }
                }
            }

            String timeFilter;
            int bucketSeconds;

            switch (range) {
                case "week":
                    timeFilter = "-7 days";
                    bucketSeconds = 10 * 60;
                    break;
                case "month":
                    timeFilter = "-30 days";
                    bucketSeconds = 60 * 60;
                    break;
                case "day":
                default:
                    timeFilter = "-1 day";
                    bucketSeconds = 0;
                    break;
            }
            
            StringBuilder json = new StringBuilder("[");
            String sql = historyQuery(timeFilter, bucketSeconds);

            try (Connection conn = DriverManager.getConnection(DB_URL);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {

                boolean first = true;
                while (rs.next()) {
                    if (!first) json.append(",");
                    first = false;

                    String ts = rs.getString("timestamp_utc");
                    int humidity = rs.getInt("humidity");
                    int rpm = rs.getInt("fan_rpm");
                    String tempSupply = nullableJsonNumber(rs, "temp_supply");
                    String tempOutside = nullableJsonNumber(rs, "temp_outside");
                    String tempExhaust = nullableJsonNumber(rs, "temp_exhaust");
                    String tempExtract = nullableJsonNumber(rs, "temp_extract");
                    String fanSpeed = nullableJsonInteger(rs, "fan_speed_level");
                    String bypassOpen = nullableJsonBypassState(rs, "bypass_open");

                    json.append(String.format(Locale.ROOT,
                        "{\"timestamp\":\"%s\", \"humidity\":%d, \"temp\":%s, \"temp_supply\":%s, " +
                        "\"temp_outside\":%s, \"temp_exhaust\":%s, \"temp_extract\":%s, " +
                        "\"rpm\":%d, \"fan_speed\":%s, \"bypass_open\":%s}",
                        ts, humidity, tempSupply, tempSupply, tempOutside, tempExhaust, tempExtract, rpm,
                        fanSpeed, bypassOpen
                    ));
                }

            } catch (Exception e) {
                // Log the error but return empty list so the dashboard doesn't break
                System.err.println("[HistoryApiHandler] Database error: " + e.getMessage());
                // If we want to return an empty list, we just continue.
                // The json StringBuilder already has "["
            }

            json.append("]");
            sendJson(t, json.toString());
        }

            static String historyQuery(String timeFilter, int bucketSeconds) {
                String columns = "timestamp, humidity, temp_supply, temp_outside, temp_exhaust, temp_extract, "
                    + "fan_rpm, fan_speed_level, bypass_open";
                String filtered = " FROM humidity_readings WHERE timestamp >= datetime('now', '" + timeFilter + "')";
                if (bucketSeconds <= 0) {
                return "SELECT strftime('%Y-%m-%dT%H:%M:%SZ', timestamp) AS timestamp_utc, "
                    + columns.substring("timestamp, ".length()) + filtered + " ORDER BY timestamp ASC";
                }

                return "WITH bucketed AS (SELECT " + columns + ", ROW_NUMBER() OVER (PARTITION BY "
                    + "CAST(strftime('%s', timestamp) AS INTEGER) / " + bucketSeconds
                    + " ORDER BY timestamp DESC) AS bucket_rank" + filtered + ") "
                    + "SELECT strftime('%Y-%m-%dT%H:%M:%SZ', timestamp) AS timestamp_utc, "
                    + columns.substring("timestamp, ".length())
                    + " FROM bucketed WHERE bucket_rank = 1 ORDER BY timestamp ASC";
            }

        private static String nullableJsonNumber(ResultSet rs, String columnName) throws SQLException {
            double value = rs.getDouble(columnName);
            return rs.wasNull() ? "null" : String.format(Locale.ROOT, "%.1f", value);
        }

        private static String nullableJsonInteger(ResultSet rs, String columnName) throws SQLException {
            int value = rs.getInt(columnName);
            return rs.wasNull() ? "null" : String.valueOf(value);
        }

        private static String nullableJsonBypassState(ResultSet rs, String columnName) throws SQLException {
            int value = rs.getInt(columnName);
            return rs.wasNull() ? "null" : jsonBypassState(normalizeBypassState(value));
        }
    }

    private void pollAndStore() {
        refreshSunStateIfNeeded();
        double historicalHumidityAverage = loadHistoricalHumidityAverage(Instant.now());
        PollResult result;
        synchronized (clientLock) {
            result = pollWithFreshConnection(historicalHumidityAverage);
        }
        if (result == null) {
            return;
        }

        persistControlState(controlStateSnapshot());

        if (saveToDatabase(result.humidity(), result.tempSupply(), result.tempOutside(), result.tempExhaust(),
            result.tempExtract(), result.supplyRpm(), result.observedFanSpeed(), result.bypassState())) {
            log("Logged: Humidity=" + result.humidity() + "%, Temp=" + result.tempSupply() + "C, RPM="
                    + result.supplyRpm() + (result.boostActive() ? " [BOOST ACTIVE]" : "")
                    + (result.defrosting() ? " [DEFROSTING]" : ""));
        } else {
            log("Read (Not Logged): Humidity=" + result.humidity() + "%, Temp=" + result.tempSupply()
                    + "C, RPM=" + result.supplyRpm() + (result.boostActive() ? " [BOOST ACTIVE]" : "")
                    + (result.defrosting() ? " [DEFROSTING]" : ""));
        }

        publishHomeAssistant(result);
    }

    private PollResult pollWithFreshConnection(double historicalHumidityAverage) {
        try {
            client.disconnect();
            log("Establishing connection to Genvex...");
            client.connect();

            int humidity = client.readDatapoint(26);
            int tempSupplyRaw = client.readDatapoint(20);
            int tempOutsideRaw = client.readDatapoint(21);
            int tempExhaustRaw = client.readDatapoint(22);
            int tempExtractRaw = client.readDatapoint(23);
            int supplyRpm = client.readDatapoint(35);
            int supplyDuty = client.readDatapoint(18);
            int extractRpm = client.readDatapoint(36);

            if (humidity == -1 || tempSupplyRaw == -1 || supplyRpm == -1 || supplyDuty == -1) {
                throw new IOException("Required datapoint is unavailable");
            }

            checkBoostLogic(humidity, historicalHumidityAverage);

            int tempSensorOffsetRaw = Integer.parseInt(System.getenv().getOrDefault("TEMP_SUPPLY_OFFSET_RAW", "-300"));
            double tempSupply = rawTemperature(tempSupplyRaw, tempSensorOffsetRaw);
            double tempOutside = rawTemperature(tempOutsideRaw, tempSensorOffsetRaw);
            double tempExhaust = rawTemperature(tempExhaustRaw, tempSensorOffsetRaw);
            double tempExtract = rawTemperature(tempExtractRaw, tempSensorOffsetRaw);

            // Defrost Detection
            boolean isDefrosting = false;
            if (supplyRpm < 100 && extractRpm > 500 && tempSupply < 10.0) {
                isDefrosting = true;
                log("STATUS: Unit appears to be in DEFROST/ANTI-ICE mode (Supply Off, Extract On, Low Temp).");
            }

            int observedFanSpeed = estimateFanSpeed(supplyRpm, supplyDuty);
            if (commandedFanSpeed == -1) {
                commandedFanSpeed = observedFanSpeed;
            }

            // Apply Fan Speed Control
            updateFanSpeed(humidity, tempSupply, tempOutside, tempExtract, observedFanSpeed, supplyDuty,
                    isDefrosting);

            int bypassState = -1;
            try {
                bypassState = normalizeBypassState(client.readDatapoint(53));
            } catch (IOException e) {
                logError("Bypass status unavailable: " + e.getMessage());
            }

            log("Polled Data: Humidity=" + humidity + "%, SupplyTempRaw=" + tempSupplyRaw
                + ", OutsideTempRaw=" + tempOutsideRaw + ", ExhaustTempRaw=" + tempExhaustRaw
                + ", ExtractTempRaw=" + tempExtractRaw
                + ", SupplyRPM=" + supplyRpm + ", SupplyDuty=" + supplyDuty + ", ExtractRPM=" + extractRpm
                + ", Bypass=" + bypassStateLabel(bypassState));

            lastHumidity = humidity;
            lastHumidityTime = System.currentTimeMillis();
            lastSupplyTemp = tempSupply;
            lastOutsideTemp = tempOutside;
            lastExhaustTemp = tempExhaust;
            lastExtractTemp = tempExtract;
            lastRpm = supplyRpm;
            lastBypassState = bypassState;
            lastObservedFanSpeed = observedFanSpeed;

            return new PollResult(humidity, tempSupply, tempOutside, tempExhaust, tempExtract, supplyRpm,
                    observedFanSpeed, bypassState, boostActive, isDefrosting);

        } catch (Exception e) {
            logError("Error polling data: " + e.getMessage());
            e.printStackTrace();
            return null;
        } finally {
            client.disconnect();
        }
    }

    private record PollResult(int humidity, double tempSupply, double tempOutside, double tempExhaust,
            double tempExtract, int supplyRpm, int observedFanSpeed, int bypassState, boolean boostActive,
            boolean defrosting) {}

    private ControlState controlStateSnapshot() {
        synchronized (clientLock) {
            return new ControlState(boostActive, boostBaselineHumidity, boostMinEndTime, boostEndTime,
                    humidityRiseCandidateBaseline);
        }
    }

    private void restoreControlState(ControlState state) {
        boostActive = BOOST_ENABLED && !monitorOnly && state.boostActive()
            && Double.isFinite(state.boostBaseline());
        boostBaselineHumidity = boostActive ? state.boostBaseline() : Double.NaN;
        boostMinEndTime = boostActive ? state.boostMinEnd() : 0;
        boostEndTime = boostActive ? state.boostEnd() : 0;
        humidityRiseCandidateBaseline = BOOST_ENABLED && !monitorOnly
            ? state.riseCandidate() : Double.NaN;
        if (boostActive) {
            log(String.format(Locale.ROOT,
                    "Restored humidity recovery toward %.1f%% after restart.", boostBaselineHumidity));
        }
    }

    private void persistControlState(ControlState state) {
        try (Connection connection = DriverManager.getConnection(DB_URL)) {
            saveControlState(connection, state);
        } catch (SQLException e) {
            logError("Failed to persist humidity recovery state: " + e.getMessage());
        }
    }

    private void publishHomeAssistant(PollResult result) {
        if (System.getenv("SUPERVISOR_TOKEN") == null) {
            return;
        }
        pendingHomeAssistantResult.set(result);
        startHomeAssistantPublisher();
    }

    private void startHomeAssistantPublisher() {
        if (homeAssistantPublishRunning.compareAndSet(false, true)) {
            homeAssistantPublisher.execute(() -> {
                try {
                    PollResult result;
                    while ((result = pendingHomeAssistantResult.getAndSet(null)) != null) {
                        updateHomeAssistant(result.humidity(), result.tempSupply(), result.tempOutside(),
                            result.tempExhaust(), result.tempExtract(), result.supplyRpm(),
                            result.observedFanSpeed(), result.bypassState());
                    }
                } finally {
                    homeAssistantPublishRunning.set(false);
                    if (pendingHomeAssistantResult.get() != null) {
                        startHomeAssistantPublisher();
                    }
                }
            });
        }
    }

    private void setFanSpeedImmediately(int speed) throws IOException, InterruptedException {
        synchronized (clientLock) {
            client.disconnect();
            try {
                client.connect();
                client.setFanSpeed(speed);
                commandedFanSpeed = speed;
            } finally {
                client.disconnect();
            }
        }
    }

    private void updateHomeAssistant(int humidity, double tempSupply, double tempOutside, double tempExhaust,
            double tempExtract, int rpm, int speed, int bypassState) {
        String token = System.getenv("SUPERVISOR_TOKEN");
        if (token == null) return;

        sendToHA("sensor.genvex_humidity", String.valueOf(humidity), "%", "humidity", token);
        sendToHA("sensor.genvex_temp_supply", String.format(Locale.ROOT, "%.1f", tempSupply), "°C", "temperature", token);
        sendTemperatureToHA("sensor.genvex_temp_outside", tempOutside, token);
        sendTemperatureToHA("sensor.genvex_temp_exhaust", tempExhaust, token);
        sendTemperatureToHA("sensor.genvex_temp_extract", tempExtract, token);
        sendToHA("sensor.genvex_fan_rpm", String.valueOf(rpm), "rpm", null, token);
        sendToHA("sensor.genvex_fan_speed", String.valueOf(speed), null, null, token);
        sendToHA("sensor.genvex_bypass", bypassStateLabel(bypassState), null, null, token);
    }

    private void sendTemperatureToHA(String entityId, double temperature, String token) {
        if (Double.isFinite(temperature)) {
            sendToHA(entityId, String.format(Locale.ROOT, "%.1f", temperature), "°C", "temperature", token);
        }
    }

    private void sendToHA(String entityId, String state, String unit, String deviceClass, String token) {
        try {
            URL url = new URL("http://supervisor/core/api/states/" + entityId);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"state\": \"").append(state).append("\",");
            json.append("\"attributes\": {");
            json.append("\"friendly_name\": \"").append(entityId.replace("sensor.genvex_", "").replace("_", " ")).append("\"");
            if (unit != null) {
                json.append(", \"unit_of_measurement\": \"").append(unit).append("\"");
            }
            if (deviceClass != null) {
                json.append(", \"device_class\": \"").append(deviceClass).append("\"");
            }
            json.append("}");
            json.append("}");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.toString().getBytes("UTF-8"));
            }
            
            int code = conn.getResponseCode();
            if (code >= 400) {
                logError("Failed to update HA entity " + entityId + ": HTTP " + code);
            }
        } catch (Exception e) {
            logError("Failed to update HA: " + e.getMessage());
        }
    }

    private void updateFanSpeed(int humidity, double tempSupply, double tempOutside, double tempExtract,
            int observedFanSpeed, int supplyDuty, boolean isDefrosting) {
        if (monitorOnly) {
            resetEveningCooling();
            log("Monitor mode active. Recommended speed: " + NORMAL_SPEED + " (Reason: Monitor Only)");
            return;
        }

        int targetSpeed = NORMAL_SPEED;
        String reason = "Normal";

        if (manualOverrideActive && System.currentTimeMillis() >= manualOverrideEndTime) {
            manualOverrideActive = false;
            manualOverrideSpeed = -1;
        }

        // Manual override takes precedence over everything
        if (manualOverrideActive && System.currentTimeMillis() < manualOverrideEndTime) {
            resetEveningCooling();
            targetSpeed = manualOverrideSpeed;
            reason = "Manual Override";
        } else if (staticRpmMode) {
            resetEveningCooling();
            targetSpeed = staticRpmSpeed;
            reason = "Static RPM Mode";
        } else if (boostActive) {
            LocalTime now = LocalTime.now();
            int coolingSpeed = selectEveningCoolingSpeed(tempSupply, tempOutside, tempExtract, now);
            targetSpeed = selectCombinedRecoverySpeed(humidity, boostBaselineHumidity,
                HUMIDITY_RISE_THRESHOLD, BOOST_SPEED, NORMAL_SPEED,
                HUMIDITY_LOW_THRESHOLD, HUMIDITY_HIGH_THRESHOLD, HUMIDITY_VERY_HIGH_THRESHOLD,
                coolingSpeed);
            if (System.currentTimeMillis() < boostMinEndTime) {
                targetSpeed = Math.max(targetSpeed, Math.min(2, BOOST_SPEED));
            }
            reason = String.format(Locale.ROOT, coolingSpeed > 0
                    ? "Humidity Recovery + Evening Cooling (delta %.1f%%)"
                    : "Humidity Recovery (delta %.1f%%)", humidity - boostBaselineHumidity);
        } else {
            LocalTime now = LocalTime.now();
            boolean isNightTime = isNight(now);

            if (humidity >= HUMIDITY_VERY_HIGH_THRESHOLD) {
                resetEveningCooling();
                targetSpeed = Math.max(3, NORMAL_SPEED);
                reason = "Humidity Very High";
            } else {
                int coolingSpeed = selectEveningCoolingSpeed(tempSupply, tempOutside, tempExtract, now);
                targetSpeed = selectAutomaticSpeed(humidity, isNightTime, coolingSpeed,
                        HUMIDITY_LOW_THRESHOLD, HUMIDITY_HIGH_THRESHOLD, NORMAL_SPEED);
                reason = coolingSpeed > 0 ? "Evening Cooling"
                        : humidity >= HUMIDITY_HIGH_THRESHOLD ? "Humidity High"
                        : isNightTime ? "Night Mode"
                        : humidity <= HUMIDITY_LOW_THRESHOLD ? "Humidity Low" : "Normal";
            }
        }
        
        boolean forceUpdate = false;
        
        // Logic:
        // If we are in Defrost mode, the unit will override our setting (making RPM 0).
        // Sending commands might be futile or fighting the controller.
        // However, we should ensure the controller at least *knows* we want speed X, so if it exits defrost, it returns to X.
        
        // Critical Fix: check supplyDuty because that tells us what the controller is *trying* to do.
        // Identify "Stopped but commanded ON"
        // If target > 0, but Supply Duty is 0 (Off), then the controller thinks it should be OFF.
        // This is where we need to force it.
        if (targetSpeed > 0 && supplyDuty == 0 && !isDefrosting) {
            log("CRITICAL: Fan Duty is 0 (OFF) but target is " + targetSpeed + ". Forcing speed update.");
            forceUpdate = true;
        }

        if (targetSpeed != observedFanSpeed || forceUpdate) {
            if (isDefrosting) {
                 log("Defrost active. Not forcing fan speed update to avoid fighting controller.");
            } else {
                try {
                    log("Adjusting Fan Speed: " + observedFanSpeed + " -> " + targetSpeed + " (Reason: " + reason + ", Force: " + forceUpdate + ")");
                    client.setFanSpeed(targetSpeed);
                    commandedFanSpeed = targetSpeed;
                } catch (Exception e) {
                    logError("Failed to set fan speed: " + e.getMessage());
                }
            }
        }
    }

    static int selectHumiditySpeed(int humidity, int lowThreshold, int highThreshold, int normalSpeed) {
        if (humidity >= highThreshold) {
            return Math.max(2, normalSpeed);
        }
        if (humidity <= lowThreshold) {
            return 1;
        }
        return normalSpeed;
    }

    static int selectAutomaticSpeed(int humidity, boolean night, int coolingSpeed,
            int lowThreshold, int highThreshold, int normalSpeed) {
        if (coolingSpeed > 0) {
            return humidity >= highThreshold
                    ? Math.max(coolingSpeed, Math.max(2, normalSpeed)) : coolingSpeed;
        }
        if (humidity >= highThreshold) {
            return Math.max(2, normalSpeed);
        }
        if (night) {
            return 1;
        }
        return selectHumiditySpeed(humidity, lowThreshold, highThreshold, normalSpeed);
    }

    static int selectDynamicBoostSpeed(int humidity, double baselineHumidity, int riseThreshold,
            int maxBoostSpeed, int normalSpeed) {
        double delta = humidity - baselineHumidity;
        if (delta <= 1.0) {
            return normalSpeed;
        }
        if (delta < riseThreshold) {
            return Math.max(normalSpeed, Math.min(2, maxBoostSpeed));
        }
        if (delta < riseThreshold * 2.0) {
            return Math.max(normalSpeed, Math.min(3, maxBoostSpeed));
        }
        return Math.max(normalSpeed, maxBoostSpeed);
    }

    static int selectBoostRecoverySpeed(int humidity, double baselineHumidity, int riseThreshold,
            int maxBoostSpeed, int normalSpeed, int lowThreshold, int highThreshold,
            int veryHighThreshold) {
        int dynamicSpeed = selectDynamicBoostSpeed(humidity, baselineHumidity, riseThreshold,
                maxBoostSpeed, normalSpeed);
        int absoluteHumiditySpeed = humidity >= veryHighThreshold
                ? Math.max(3, normalSpeed)
                : selectHumiditySpeed(humidity, lowThreshold, highThreshold, normalSpeed);
        return Math.max(dynamicSpeed, absoluteHumiditySpeed);
    }

    static int selectCombinedRecoverySpeed(int humidity, double baselineHumidity, int riseThreshold,
            int maxBoostSpeed, int normalSpeed, int lowThreshold, int highThreshold,
            int veryHighThreshold, int coolingSpeed) {
        return Math.max(coolingSpeed, selectBoostRecoverySpeed(humidity, baselineHumidity, riseThreshold,
                maxBoostSpeed, normalSpeed, lowThreshold, highThreshold, veryHighThreshold));
    }

    static double updateHumidityRiseCandidate(double candidateBaseline, int previousHumidity,
            int currentHumidity, double historicalAverage) {
        if (Double.isFinite(candidateBaseline)) {
            return currentHumidity <= candidateBaseline
                    ? Double.NaN : candidateBaseline;
        }
        if (currentHumidity > previousHumidity) {
            return Double.isFinite(historicalAverage)
                    ? Math.min(previousHumidity, historicalAverage) : previousHumidity;
        }
        return Double.NaN;
    }

    static boolean hasHumidityRise(int humidity, double baselineHumidity, int riseThreshold) {
        return Double.isFinite(baselineHumidity)
                && humidity - baselineHumidity >= riseThreshold;
    }

    static boolean shouldDeactivateBoost(long now, long minimumEndTime, int humidity,
            double baselineHumidity, int recoveryTolerance) {
        return now >= minimumEndTime && Double.isFinite(baselineHumidity)
                && humidity <= baselineHumidity + recoveryTolerance;
    }

    static double historicalHumidityAverage(Connection connection, Instant endExclusive,
            int windowMinutes) throws SQLException {
        String sql = "SELECT AVG(humidity) FROM humidity_readings "
                + "WHERE timestamp >= ? AND timestamp < ?";
        DateTimeFormatter sqliteTimestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sqliteTimestamp.format(endExclusive.minusSeconds(windowMinutes * 60L)));
            statement.setString(2, sqliteTimestamp.format(endExclusive));
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    double average = result.getDouble(1);
                    return result.wasNull() ? Double.NaN : average;
                }
            }
        }
        return Double.NaN;
    }

    private int selectEveningCoolingSpeed(double tempSupply, double tempOutside, double tempExtract, LocalTime now) {
        if (!EVENING_COOLING_ENABLED || !isAfterSunset(now)) {
            resetEveningCooling();
            return 0;
        }

        long currentTime = System.currentTimeMillis();
        boolean stalled = eveningCoolingActive && EveningCoolingPolicy.hasStalled(
            coolingBaselineIndoorTemp, tempExtract, currentTime - coolingBaselineTime,
            COOLING_ESCALATION_MS, COOLING_PROGRESS_C);
        int selectedSpeed = EveningCoolingPolicy.selectSpeed(
            eveningCoolingSpeed, tempSupply, tempOutside, tempExtract,
                COOLING_STOP_TEMP, COOLING_START_TEMP, COOLING_MIN_SUPPLY_TEMP, stalled);

        if (selectedSpeed == 0) {
            if (eveningCoolingActive) {
                log(String.format(Locale.ROOT, "Evening cooling complete: indoor %.1fC, outside %.1fC, supply %.1fC.",
                        tempExtract, tempOutside, tempSupply));
            }
            resetEveningCooling();
            return 0;
        }

        if (!eveningCoolingActive) {
            coolingBaselineIndoorTemp = tempExtract;
            coolingBaselineTime = currentTime;
            log(String.format(Locale.ROOT, "Evening cooling started at speed %d: indoor %.1fC, outside %.1fC, supply %.1fC.",
                    selectedSpeed, tempExtract, tempOutside, tempSupply));
        } else if (tempExtract <= coolingBaselineIndoorTemp - COOLING_PROGRESS_C) {
            coolingBaselineIndoorTemp = tempExtract;
            coolingBaselineTime = currentTime;
        }
        if (selectedSpeed > eveningCoolingSpeed && eveningCoolingActive) {
            log(String.format(Locale.ROOT, "Evening cooling escalated to speed %d after insufficient indoor temperature improvement (%.1fC).",
                    selectedSpeed, tempExtract));
        }

        eveningCoolingActive = true;
        eveningCoolingSpeed = selectedSpeed;
        return selectedSpeed;
    }

    private void resetEveningCooling() {
        eveningCoolingActive = false;
        eveningCoolingSpeed = 0;
        coolingBaselineIndoorTemp = Double.NaN;
        coolingBaselineTime = 0;
    }

    private void refreshSunStateIfNeeded() {
        String token = System.getenv("SUPERVISOR_TOKEN");
        long currentTime = System.currentTimeMillis();
        if (token == null || currentTime - lastSunStateCheck < SUN_STATE_CACHE_MS) {
            return;
        }

        lastSunStateCheck = currentTime;
        try {
            URL url = new URL("http://supervisor/core/api/states/sun.sun");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            if (conn.getResponseCode() < 400) {
                String response = new String(conn.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                lastSunBelowHorizon = response.matches("(?s).*\\\"state\\\"\\s*:\\s*\\\"below_horizon\\\".*");
                sunStateAvailable = true;
                return;
            }
        } catch (Exception e) {
            logError("Failed to read Home Assistant sun state; using configured cooling window: " + e.getMessage());
        }
        sunStateAvailable = false;
    }

    private boolean isAfterSunset(LocalTime now) {
        if (System.getenv("SUPERVISOR_TOKEN") != null && sunStateAvailable) {
            return lastSunBelowHorizon;
        }

        return isCoolingFallbackWindow(now, COOLING_FALLBACK_START, NIGHT_END);
    }

    static boolean isCoolingFallbackWindow(LocalTime time, LocalTime start, LocalTime end) {
        return isTimeInRange(time, start, end);
    }

    static double rawTemperature(int rawValue, int offsetRaw) {
        return rawValue == -1 ? Double.NaN : (rawValue + offsetRaw) / 10.0;
    }
    
    class RestartApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!t.getRequestMethod().equalsIgnoreCase("POST")) {
                 sendError(t, 405, "Method Not Allowed");
                 return;
            }
            
            log("Received SYSTEM RESTART command.");
            
            new Thread(() -> {
                 try {
                     log("Restart sequence: Setting fan to 0...");
                     setFanSpeedImmediately(0);
                     Thread.sleep(5000);
                     log("Restart sequence: Setting fan to 1...");
                     setFanSpeedImmediately(1);
                     Thread.sleep(5000);
                     log("Restart sequence complete.");
                 } catch (Exception e) {
                     logError("Restart sequence failed: " + e.getMessage());
                 }
            }).start();
            
            sendJson(t, "{\"status\": \"ok\", \"message\": \"Restart sequence initiated\"}");
        }
    }

    class SystemModeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (t.getRequestMethod().equalsIgnoreCase("POST")) {
                 try {
                     java.io.InputStream is = t.getRequestBody();
                     String body = new String(is.readAllBytes());
                     log("System Mode Request: " + body);
                     
                     String val = getJsonValue(body, "monitor_only", null);
                     if (val != null && (val.equalsIgnoreCase("true") || val.equalsIgnoreCase("false"))) {
                        boolean requestedMonitorOnly = Boolean.parseBoolean(val);
                        synchronized (clientLock) {
                            monitorOnly = requestedMonitorOnly;
                            if (requestedMonitorOnly) {
                                staticRpmMode = false;
                                manualOverrideActive = false;
                                manualOverrideSpeed = -1;
                                manualOverrideEndTime = 0;
                                deactivateBoost();
                                resetEveningCooling();
                            }
                        }
                        persistControlState(controlStateSnapshot());
                        log("System Monitor Mode updated to: " + monitorOnly);
                        sendJson(t, "{\"status\": \"ok\", \"monitor_only\": " + monitorOnly + "}");
                     } else {
                        sendError(t, 400, "monitor_only must be true or false");
                     }
                 } catch (Exception e) {
                     sendError(t, 500, e.getMessage());
                 }
            } else {
                 sendError(t, 405, "Method Not Allowed");
            }
        }
    }

    private boolean isNight(LocalTime time) {
        return isTimeInRange(time, NIGHT_START, NIGHT_END);
    }

    private static boolean isTimeInRange(LocalTime time, LocalTime start, LocalTime end) {
        if (start.isBefore(end)) {
            return !time.isBefore(start) && !time.isAfter(end);
        } else {
            return !time.isBefore(start) || !time.isAfter(end);
        }
    }

    private void checkBoostLogic(int currentHumidity, double historicalHumidityAverage) {
        if (!BOOST_ENABLED || monitorOnly || staticRpmMode) return;
        if (lastHumidity == -1) return; // First run, can't calculate delta

        long now = System.currentTimeMillis();
        
        if (!boostActive) {
            // Check if the time gap is too large (e.g., missed polls due to errors)
            // If the gap is more than 2.5x the poll interval, we skip the check to avoid false positives
            long timeGap = now - lastHumidityTime;
            long maxGap = (long) (POLL_INTERVAL * 2.5 * 1000);
            
            if (timeGap > maxGap) {
                log("Skipping boost check due to long gap between readings (" + (timeGap/1000) + "s). Re-establishing baseline.");
                return;
            }

                humidityRiseCandidateBaseline = updateHumidityRiseCandidate(humidityRiseCandidateBaseline,
                        lastHumidity, currentHumidity, historicalHumidityAverage);
                double baselineHumidity = Double.isFinite(humidityRiseCandidateBaseline)
                    ? humidityRiseCandidateBaseline
                    : Double.isFinite(historicalHumidityAverage) ? historicalHumidityAverage : lastHumidity;
            if (hasHumidityRise(currentHumidity, baselineHumidity, HUMIDITY_RISE_THRESHOLD)) {
                log(String.format(Locale.ROOT,
                        "Humidity rise detected (%d%% current, historical average %.1f%%). Activating recovery.",
                        currentHumidity, baselineHumidity));
                activateBoost(currentHumidity, baselineHumidity);
                humidityRiseCandidateBaseline = Double.NaN;
            }
        } else {
            if (shouldDeactivateBoost(now, boostEndTime, currentHumidity,
                    boostBaselineHumidity, HUMIDITY_RECOVERY_TOLERANCE)) {
                log(String.format(Locale.ROOT,
                        "Humidity recovered (%d%%, historical average %.1f%%). Deactivating Boost.",
                        currentHumidity, boostBaselineHumidity));
                deactivateBoost();
            } else if (now >= boostEndTime && !boostExtensionLogged) {
                boostExtensionLogged = true;
                log(String.format(Locale.ROOT,
                        "Initial boost duration complete; continuing dynamic recovery at %d%% toward historical average %.1f%%.",
                        currentHumidity, boostBaselineHumidity));
            }
        }
    }

    private void activateBoost(int activationHumidity, double baselineHumidity) {
        boostActive = true;
        boostBaselineHumidity = baselineHumidity;
        boostExtensionLogged = false;
        long now = System.currentTimeMillis();
        boostMinEndTime = now + boostMinimumDurationMillis(BOOST_DURATION_MS);
        boostEndTime = now + BOOST_DURATION_MS;
        log(String.format(Locale.ROOT,
                "Boost activated at %d%% humidity with %.1f%% recovery baseline. Minimum duration: %d min, initial boost window: %d min.",
                activationHumidity, baselineHumidity, boostMinimumDurationMillis(BOOST_DURATION_MS) / 60000,
                BOOST_DURATION_MS / 60000));
    }

    static long boostMinimumDurationMillis(long configuredDurationMillis) {
        return Math.min(10 * 60 * 1000L, configuredDurationMillis);
    }

    private void deactivateBoost() {
        boostActive = false;
        boostBaselineHumidity = Double.NaN;
        boostExtensionLogged = false;
        humidityRiseCandidateBaseline = Double.NaN;
        // Speed change will be handled by updateFanSpeed()
    }

    private double loadHistoricalHumidityAverage(Instant endExclusive) {
        try (Connection connection = DriverManager.getConnection(DB_URL)) {
            return historicalHumidityAverage(connection, endExclusive, HUMIDITY_BASELINE_MINUTES);
        } catch (SQLException e) {
            logError("Failed to load historical humidity average: " + e.getMessage());
            return Double.NaN;
        }
    }

    class UdluftningApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!t.getRequestMethod().equalsIgnoreCase("POST")) {
                sendError(t, 405, "Method Not Allowed");
                return;
            }

            java.io.InputStream is = t.getRequestBody();
            String payload = new String(is.readAllBytes());

            int level = NORMAL_SPEED;
            int durationMinutes = 30;
            
            String levelStr = getJsonValue(payload, "level", String.valueOf(NORMAL_SPEED));
            String durationStr = getJsonValue(payload, "duration_minutes", "30");
            
            try {
                level = Integer.parseInt(levelStr);
                durationMinutes = Integer.parseInt(durationStr);
            } catch (NumberFormatException e) {
                // Ignore, use defaults
            }

            if (level < 0 || level > 4) level = NORMAL_SPEED;
            if (durationMinutes < 1) durationMinutes = 30;

            try {
                synchronized (clientLock) {
                    setFanSpeedImmediately(level);
                    manualOverrideActive = true;
                    manualOverrideSpeed = level;
                    manualOverrideEndTime = System.currentTimeMillis() + (durationMinutes * 60L * 1000L);
                    monitorOnly = false;
                    commandedFanSpeed = level;
                }
            } catch (Exception e) {
                logError("Failed to set fan speed via Udluftning: " + e.getMessage());
                sendError(t, 502, "Genvex did not acknowledge the fan speed command");
                return;
            }

            String json = String.format("{\"ok\":true,\"level\":%d,\"minutes\":%d,\"until\":%d}", level, durationMinutes, manualOverrideEndTime);
            sendJson(t, json);
        }
    }

    class StaticRpmApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (t.getRequestMethod().equalsIgnoreCase("POST")) {
                java.io.InputStream is = t.getRequestBody();
                String payload = new String(is.readAllBytes());
                
                String enabledStr = getJsonValue(payload, "enabled", null);
                String speedStr = getJsonValue(payload, "speed", null);
                
                if (enabledStr == null || (!enabledStr.equalsIgnoreCase("true") && !enabledStr.equalsIgnoreCase("false"))) {
                    sendError(t, 400, "enabled must be true or false");
                    return;
                }

                boolean requestedEnabled = Boolean.parseBoolean(enabledStr);
                int requestedSpeed = staticRpmSpeed;
                if (speedStr != null) {
                    try {
                        requestedSpeed = Integer.parseInt(speedStr);
                    } catch (NumberFormatException e) {
                        sendError(t, 400, "speed must be an integer from 0 to 4");
                        return;
                    }
                }
                if (requestedSpeed < 0 || requestedSpeed > 4) {
                    sendError(t, 400, "speed must be an integer from 0 to 4");
                    return;
                }
                
                try {
                    synchronized (clientLock) {
                        if (requestedEnabled) {
                            setFanSpeedImmediately(requestedSpeed);
                            monitorOnly = false;
                            manualOverrideActive = false;
                            manualOverrideSpeed = -1;
                            manualOverrideEndTime = 0;
                            deactivateBoost();
                            resetEveningCooling();
                            commandedFanSpeed = requestedSpeed;
                            log("Static RPM Mode Activated: Speed " + requestedSpeed);
                        } else {
                            log("Static RPM Mode Deactivated. Resuming auto control.");
                        }

                        staticRpmMode = requestedEnabled;
                        staticRpmSpeed = requestedSpeed;
                    }
                    persistControlState(controlStateSnapshot());
                } catch (Exception e) {
                    logError("Failed to set fan speed for Static Mode: " + e.getMessage());
                    sendError(t, 502, "Genvex did not acknowledge the fan speed command");
                    return;
                }
            }
            
            String json = String.format("{\"enabled\":%b,\"speed\":%d}", staticRpmMode, staticRpmSpeed);
            sendJson(t, json);
        }
    }

    private boolean saveToDatabase(int humidity, double tempSupply, double tempOutside, double tempExhaust,
            double tempExtract, int rpm, int fanSpeed, int bypassState) {
        String sql = "INSERT INTO humidity_readings (humidity, temp_supply, temp_outside, temp_exhaust, " +
                     "temp_extract, fan_rpm, fan_speed_level, bypass_open) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, humidity);
            pstmt.setDouble(2, tempSupply);
            setNullableDouble(pstmt, 3, tempOutside);
            setNullableDouble(pstmt, 4, tempExhaust);
            setNullableDouble(pstmt, 5, tempExtract);
            pstmt.setInt(6, rpm);
            pstmt.setInt(7, fanSpeed);
            setNullableInteger(pstmt, 8, bypassState);
            pstmt.executeUpdate();
            
            if (dbErrorCount > 0) {
                log("Database connection restored.");
                dbErrorCount = 0;
            }
            return true;

        } catch (SQLException e) {
            dbErrorCount++;
            if (dbErrorCount <= 5) {
                logError("Database error: " + e.getMessage());
            } else if (dbErrorCount == 6) {
                logError("Database error: " + e.getMessage() + " (Suppressing further DB errors)");
            }
            return false;
        }
    }

    private static void setNullableDouble(PreparedStatement pstmt, int parameterIndex, double value) throws SQLException {
        if (Double.isFinite(value)) {
            pstmt.setDouble(parameterIndex, value);
        } else {
            pstmt.setNull(parameterIndex, java.sql.Types.REAL);
        }
    }

    private static void setNullableInteger(PreparedStatement pstmt, int parameterIndex, int value) throws SQLException {
        if (value >= 0) {
            pstmt.setInt(parameterIndex, value);
        } else {
            pstmt.setNull(parameterIndex, java.sql.Types.INTEGER);
        }
    }

    static int normalizeBypassState(int rawValue) {
        return rawValue < 0 ? -1 : rawValue == 0 ? 0 : 1;
    }

    static String jsonBypassState(int bypassState) {
        return bypassState < 0 ? "null" : String.valueOf(bypassState == 1);
    }

    private static String bypassStateLabel(int bypassState) {
        return bypassState < 0 ? "unknown" : bypassState == 1 ? "open" : "closed";
    }

    private void cleanupOldData() {
        // Retention period: 1 month
        String sql = "DELETE FROM humidity_readings WHERE timestamp < datetime('now', '-1 month')";
        
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            int deleted = pstmt.executeUpdate();
            log("Cleanup: Removed " + deleted + " old records.");

        } catch (SQLException e) {
            logError("Cleanup error: " + e.getMessage());
        }
    }

    private int estimateFanSpeed(int rpm, int duty) {
        if (rpm < 100) {
            return 0;
        }
        int pct = duty / 100; // e.g. 5000 -> 50
        if (pct < 15) return 0;
        if (pct < 40) return 1; // Speed 1 (usually ~30%)
        if (pct < 60) return 2; // Speed 2 (usually ~50%)
        if (pct < 85) return 3; // Speed 3 (usually ~70-80%)
        return 4;               // Speed 4 (usually 100%)
    }

    private void log(String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        System.out.println("[" + timestamp + "] [" + sessionId + "] " + message);
    }

    private void logError(String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        System.err.println("[" + timestamp + "] [" + sessionId + "] " + message);
    }

    public static void main(String[] args) {
        String ip = System.getenv().getOrDefault("GENVEX_IP", "");
        String email = System.getenv().getOrDefault("GENVEX_EMAIL", "");
        
        if (ip.isEmpty() || email.isEmpty()) {
            System.err.println("Error: GENVEX_IP and GENVEX_EMAIL environment variables must be set.");
            System.err.println("Configure these in the add-on settings or set them as environment variables.");
            System.exit(1);
        }
        
        HumidityMonitor monitor = new HumidityMonitor(ip, email);
        monitor.start();
    }
}
