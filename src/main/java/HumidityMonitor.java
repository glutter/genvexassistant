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
    private static final int MAX_MANUAL_OVERRIDE_MINUTES = 24 * 60;

    private final GenvexClient client;
    private final Object clientLock = new Object();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final ExecutorService homeAssistantPublisher = Executors.newSingleThreadExecutor();
    private final AtomicReference<PollResult> pendingHomeAssistantResult = new AtomicReference<>();
    private final AtomicBoolean homeAssistantPublishRunning = new AtomicBoolean();
    private final AtomicBoolean restartInProgress = new AtomicBoolean();
    private final String sessionId = java.util.UUID.randomUUID().toString().substring(0, 6);

    // Configuration
    private static final int POLL_INTERVAL = Integer.parseInt(System.getenv().getOrDefault("POLL_INTERVAL", "30"));
    private volatile boolean monitorOnly = Boolean.parseBoolean(System.getenv().getOrDefault("MONITOR_ONLY", "false"));

    // Boost Configuration
    private static final boolean BOOST_ENABLED = Boolean.parseBoolean(System.getenv().getOrDefault("BOOST_ENABLED", "true"));
    private static final int HUMIDITY_RISE_THRESHOLD = Integer.parseInt(System.getenv().getOrDefault("HUMIDITY_RISE_THRESHOLD", "4"));
    private static final int BOOST_SPEED = Integer.parseInt(System.getenv().getOrDefault("BOOST_SPEED", "3"));
    private static final int NORMAL_SPEED = Integer.parseInt(System.getenv().getOrDefault("NORMAL_SPEED", "1"));
    private static final long BOOST_DURATION_MS = Integer.parseInt(System.getenv().getOrDefault("BOOST_DURATION_MINUTES", "15")) * 60 * 1000L;
    private static final int HUMIDITY_BASELINE_MINUTES = Integer.parseInt(System.getenv().getOrDefault("HUMIDITY_BASELINE_MINUTES", "30"));
    private static final int HUMIDITY_RECOVERY_TOLERANCE = Integer.parseInt(System.getenv().getOrDefault("HUMIDITY_RECOVERY_TOLERANCE", "1"));

    // General Control Configuration
    private static final int HUMIDITY_VERY_HIGH_THRESHOLD = Integer.parseInt(System.getenv().getOrDefault("HUMIDITY_VERY_HIGH_THRESHOLD", "80"));
    private static final int HUMIDITY_HIGH_THRESHOLD = Integer.parseInt(System.getenv().getOrDefault("HUMIDITY_HIGH_THRESHOLD", "65"));
    private static final int HUMIDITY_LOW_THRESHOLD = Integer.parseInt(System.getenv().getOrDefault("HUMIDITY_LOW_THRESHOLD", "30"));
        private static final HumidityPolicy HUMIDITY_POLICY = new HumidityPolicy(
            HUMIDITY_RISE_THRESHOLD, HUMIDITY_RECOVERY_TOLERANCE, BOOST_SPEED, NORMAL_SPEED,
            HUMIDITY_LOW_THRESHOLD, HUMIDITY_HIGH_THRESHOLD, HUMIDITY_VERY_HIGH_THRESHOLD);
    private static final LocalTime NIGHT_START = LocalTime.parse(System.getenv().getOrDefault("NIGHT_START", "22:00"));
    private static final LocalTime NIGHT_END = LocalTime.parse(System.getenv().getOrDefault("NIGHT_END", "06:30"));

    // Fan Command Pacing (anti-oscillation)
    private static final int HUMIDITY_HYSTERESIS = Integer.parseInt(System.getenv().getOrDefault("HUMIDITY_HYSTERESIS", "3"));
    private static final FanCommandPacing FAN_PACING = new FanCommandPacing(
            Integer.parseInt(System.getenv().getOrDefault("FAN_MIN_COMMAND_INTERVAL_SECONDS", "120")) * 1000L,
            Integer.parseInt(System.getenv().getOrDefault("FAN_RETRY_INTERVAL_SECONDS", "120")) * 1000L,
            Integer.parseInt(System.getenv().getOrDefault("FAN_MAX_RETRY_INTERVAL_SECONDS", "1800")) * 1000L,
            Integer.parseInt(System.getenv().getOrDefault("FAN_RETRY_ATTEMPTS_BEFORE_BACKOFF", "3")));
    private static final int BYPASS_UNKNOWN_TOLERANCE = Integer.parseInt(System.getenv().getOrDefault("BYPASS_UNKNOWN_TOLERANCE", "3"));

    // Heat Loss Guard Configuration (adaptive step-down while the house is losing heat)
    private static final boolean HEAT_LOSS_GUARD_ENABLED = Boolean.parseBoolean(System.getenv().getOrDefault("HEAT_LOSS_GUARD_ENABLED", "true"));
    private static final double HEAT_LOSS_INDOOR_TEMP_C = Double.parseDouble(System.getenv().getOrDefault("HEAT_LOSS_INDOOR_TEMP_C", "23.0"));
    private static final double HEAT_LOSS_TEMP_DELTA_C = Double.parseDouble(System.getenv().getOrDefault("HEAT_LOSS_TEMP_DELTA_C", "5.0"));
    private static final long HEAT_LOSS_PROBE_MS = Integer.parseInt(System.getenv().getOrDefault("HEAT_LOSS_PROBE_MINUTES", "10")) * 60 * 1000L;
    private static final double HEAT_LOSS_PROGRESS_G_PER_KG = Double.parseDouble(System.getenv().getOrDefault("HEAT_LOSS_PROGRESS_G_PER_KG", "0.15"));
    private static final double HEAT_LOSS_PEAK_MARGIN_G_PER_KG = Double.parseDouble(System.getenv().getOrDefault("HEAT_LOSS_PEAK_MARGIN_G_PER_KG", "0.3"));
    private static final int HEAT_LOSS_OVERRIDE_HUMIDITY = Integer.parseInt(System.getenv().getOrDefault(
            "HEAT_LOSS_OVERRIDE_HUMIDITY", String.valueOf(HUMIDITY_VERY_HIGH_THRESHOLD)));
    // The floor is never below speed 1: the humidity policy may legally command 0, but a heat-loss limiter
    // must not be the thing that stops ventilation. With the floor at 1 a target of 0 simply disarms it.
    private static final HeatLossGuardPolicy.HeatLossGuardConfig HEAT_LOSS_CONFIG =
            new HeatLossGuardPolicy.HeatLossGuardConfig(HEAT_LOSS_GUARD_ENABLED, HEAT_LOSS_INDOOR_TEMP_C,
                    HEAT_LOSS_TEMP_DELTA_C, HEAT_LOSS_PROBE_MS, HEAT_LOSS_PROGRESS_G_PER_KG,
                    HEAT_LOSS_PEAK_MARGIN_G_PER_KG, HEAT_LOSS_OVERRIDE_HUMIDITY, Math.max(1, NORMAL_SPEED));

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
    private int lastKnownBypassState = -1;
    private int unknownBypassReads = 0;
    private boolean boostActive = false;
    private long boostEndTime = 0;
    private double boostBaselineHumidity = Double.NaN;
    // Mixing-ratio twin of boostBaselineHumidity, deliberately NOT persisted: the boost_baseline column stays
    // a relative humidity so an older binary can still read it. A boost restored from disk therefore has a
    // NaN moisture baseline and exits on the legacy relative-humidity comparison instead.
    private double boostBaselineMoisture = Double.NaN;
    private int commandedFanSpeed = -1;
    // The speed humidity control asked for, before the heat-loss guard limited it. Kept apart from
    // commandedFanSpeed because it is the hysteresis latch: feeding the guard's own reduced write back in
    // would let the guard erode the target it measures against, one deadband at a time.
    private int policyTargetSpeed = -1;
    private long lastFanCommandTime = 0;
    private int lastObservedFanSpeed = -1;
    private int fanCommandAttempts = 0;
    private boolean setpointReadbackUnavailableLogged = false;
    private boolean setpointReadbackAvailable = false;
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
    private HeatLossGuardPolicy.HeatLossState heatLossState = HeatLossGuardPolicy.HeatLossState.IDLE;
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
        validateRuntimeConfiguration(POLL_INTERVAL, COOLING_ESCALATION_MS, NIGHT_START, NIGHT_END,
                COOLING_START_TEMP, COOLING_STOP_TEMP, COOLING_MIN_SUPPLY_TEMP);
        validateFanPacingConfiguration(FAN_PACING, HUMIDITY_HYSTERESIS, HUMIDITY_LOW_THRESHOLD,
                HUMIDITY_HIGH_THRESHOLD);
        validateHeatLossConfiguration(HEAT_LOSS_CONFIG);

        // Initialize Database
        initializeDatabase();

        // Start Web Server
        startWebServer();

        log("Starting polling service with Session ID: " + sessionId);

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

    static void validateRuntimeConfiguration(int pollIntervalSeconds, long coolingEscalationMillis,
            LocalTime nightStart, LocalTime nightEnd, double coolingStartTemp,
            double coolingStopTemp, double coolingMinSupplyTemp) {
        if (pollIntervalSeconds <= 0) {
            throw new IllegalArgumentException("POLL_INTERVAL must be positive");
        }
        if (coolingEscalationMillis <= 0) {
            throw new IllegalArgumentException("COOLING_ESCALATION_MINUTES must be positive");
        }
        if (nightStart.equals(nightEnd)) {
            throw new IllegalArgumentException("NIGHT_START and NIGHT_END must be different");
        }
        if (!Double.isFinite(coolingStartTemp) || !Double.isFinite(coolingStopTemp)
                || !Double.isFinite(coolingMinSupplyTemp)) {
            throw new IllegalArgumentException("Cooling temperatures must be finite");
        }
        if (coolingStartTemp < coolingStopTemp) {
            throw new IllegalArgumentException("COOLING_START_TEMP must be at least COOLING_STOP_TEMP");
        }
    }

    static void validateFanPacingConfiguration(FanCommandPacing pacing, int humidityHysteresis,
            int lowThreshold, int highThreshold) {
        if (pacing.minIntervalMillis() <= 0) {
            throw new IllegalArgumentException("FAN_MIN_COMMAND_INTERVAL_SECONDS must be positive");
        }
        if (pacing.retryIntervalMillis() <= 0) {
            throw new IllegalArgumentException("FAN_RETRY_INTERVAL_SECONDS must be positive");
        }
        if (pacing.maxRetryIntervalMillis() < pacing.retryIntervalMillis()) {
            throw new IllegalArgumentException(
                    "FAN_MAX_RETRY_INTERVAL_SECONDS must be at least FAN_RETRY_INTERVAL_SECONDS");
        }
        if (pacing.attemptsBeforeBackoff() < 1) {
            throw new IllegalArgumentException("FAN_RETRY_ATTEMPTS_BEFORE_BACKOFF must be positive");
        }
        if (humidityHysteresis < 0 || humidityHysteresis >= highThreshold - lowThreshold) {
            throw new IllegalArgumentException(
                    "HUMIDITY_HYSTERESIS must be from 0 to the gap between the humidity thresholds");
        }
    }

    static void validateHeatLossConfiguration(HeatLossGuardPolicy.HeatLossGuardConfig config) {
        if (!Double.isFinite(config.indoorCeilingC())) {
            throw new IllegalArgumentException("HEAT_LOSS_INDOOR_TEMP_C must be finite");
        }
        if (!Double.isFinite(config.tempDeltaC()) || config.tempDeltaC() <= 0) {
            throw new IllegalArgumentException("HEAT_LOSS_TEMP_DELTA_C must be positive");
        }
        if (config.probeWindowMillis() <= 0) {
            throw new IllegalArgumentException("HEAT_LOSS_PROBE_MINUTES must be positive");
        }
        if (!Double.isFinite(config.progressGramsPerKg()) || config.progressGramsPerKg() <= 0) {
            throw new IllegalArgumentException("HEAT_LOSS_PROGRESS_G_PER_KG must be positive");
        }
        if (!Double.isFinite(config.peakMarginGramsPerKg()) || config.peakMarginGramsPerKg() <= 0) {
            throw new IllegalArgumentException("HEAT_LOSS_PEAK_MARGIN_G_PER_KG must be positive");
        }
        if (config.overrideHumidityPct() < 0 || config.overrideHumidityPct() > 100) {
            throw new IllegalArgumentException("HEAT_LOSS_OVERRIDE_HUMIDITY must be from 0 to 100");
        }
        if (config.floorSpeed() < 1 || config.floorSpeed() > 4) {
            throw new IllegalArgumentException("Heat loss guard floor speed must be from 1 to 4");
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
                     "bypass_open INTEGER, " +
                     "commanded_speed INTEGER, " +
                     "supply_duty INTEGER)";
        
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
        addColumnIfMissing(conn, "commanded_speed", "INTEGER");
        addColumnIfMissing(conn, "supply_duty", "INTEGER");
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
                    + "boost_end INTEGER NOT NULL DEFAULT 0, "
                    + "boost_min_end INTEGER NOT NULL DEFAULT 0, "
                    + "rise_candidate REAL)");
            addControlStateColumnIfMissing(connection, "boost_min_end",
                    "INTEGER NOT NULL DEFAULT 0");
            addControlStateColumnIfMissing(connection, "rise_candidate", "REAL");
            statement.execute("INSERT OR IGNORE INTO control_state (id) VALUES (1)");
        }
    }

    private static void addControlStateColumnIfMissing(Connection connection, String columnName,
            String columnType) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(control_state)")) {
            while (result.next()) {
                if (columnName.equals(result.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE control_state ADD COLUMN " + columnName + " " + columnType);
        }
    }

    static void saveControlState(Connection connection, ControlState state) throws SQLException {
        String sql = "UPDATE control_state SET boost_active = ?, boost_baseline = ?, "
                + "boost_end = ? WHERE id = 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, state.boostActive() ? 1 : 0);
            setNullableDouble(statement, 2, state.boostBaseline());
            statement.setLong(3, state.boostEnd());
            statement.executeUpdate();
        }
    }

    static ControlState loadControlState(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
            ResultSet result = statement.executeQuery("SELECT boost_active, boost_baseline, "
                      + "boost_end FROM control_state WHERE id = 1")) {
            if (result.next()) {
                boolean active = result.getInt("boost_active") == 1;
                double baseline = result.getDouble("boost_baseline");
                if (result.wasNull()) baseline = Double.NaN;
                long end = result.getLong("boost_end");
                return new ControlState(active, baseline, end);
            }
        }
        return new ControlState(false, Double.NaN, 0);
    }

    static record ControlState(boolean boostActive, double boostBaseline, long boostEnd) {}

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
                        humidityRecoveryTarget(boostBaselineHumidity),
                    false,
                        eveningCoolingActive, eveningCoolingSpeed, staticRpmMode, staticRpmSpeed, monitorOnly,
                        manualOverrideActive && now < manualOverrideEndTime,
                        Math.max(0, (manualOverrideEndTime - now) / 1000),
                        Math.max(0, (boostEndTime - now) / 1000),
                        heatLossState.stepDown() > 0, heatLossState.stepDown());
            }

            String json = String.format(Locale.ROOT,
                "{\"humidity\":%d, \"temp\":%s, \"temp_supply\":%s, \"temp_outside\":%s, \"temp_exhaust\":%s, \"temp_extract\":%s, \"rpm\":%d, \"bypass_open\":%s, \"fan_speed\":%d, \"commanded_speed\":%d, \"boost\":%b, \"boost_recovery_target\":%s, \"boost_extended\":%b, \"evening_cooling\":%b, \"evening_cooling_speed\":%d, \"static_mode\":%b, \"static_speed\":%d, \"monitor_only\":%b, \"manual_override_active\":%b, \"manual_override_secs_left\":%d, \"boost_secs_left\":%d, \"heat_loss_guard\":%b, \"heat_loss_step_down\":%d}",
                snapshot.humidity(), jsonTemperature(snapshot.tempSupply()), jsonTemperature(snapshot.tempSupply()),
                jsonTemperature(snapshot.tempOutside()), jsonTemperature(snapshot.tempExhaust()),
                jsonTemperature(snapshot.tempExtract()), snapshot.rpm(), jsonBypassState(snapshot.bypassState()),
                snapshot.observedFanSpeed(),
                snapshot.commandedFanSpeed(), snapshot.boostActive(),
                jsonTemperature(snapshot.boostRecoveryTarget()), snapshot.boostExtended(),
                snapshot.eveningCoolingActive(),
                snapshot.eveningCoolingSpeed(), snapshot.staticMode(), snapshot.staticSpeed(), snapshot.monitorOnly(),
                snapshot.manualOverrideActive(), snapshot.manualOverrideSecsLeft(), snapshot.boostSecsLeft(),
                snapshot.heatLossGuardActive(), snapshot.heatLossStepDown()
            );
            sendJson(t, json);
        }
    }

        private record LiveSnapshot(int humidity, double tempSupply, double tempOutside, double tempExhaust,
            double tempExtract, int rpm, int bypassState, int observedFanSpeed, int commandedFanSpeed,
            boolean boostActive,
            double boostRecoveryTarget, boolean boostExtended,
            boolean eveningCoolingActive, int eveningCoolingSpeed, boolean staticMode, int staticSpeed,
            boolean monitorOnly, boolean manualOverrideActive, long manualOverrideSecsLeft, long boostSecsLeft,
            boolean heatLossGuardActive, int heatLossStepDown) {}

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
                    String commandedSpeed = nullableJsonInteger(rs, "commanded_speed");
                    String supplyDuty = nullableJsonInteger(rs, "supply_duty");

                    json.append(String.format(Locale.ROOT,
                        "{\"timestamp\":\"%s\", \"humidity\":%d, \"temp\":%s, \"temp_supply\":%s, " +
                        "\"temp_outside\":%s, \"temp_exhaust\":%s, \"temp_extract\":%s, " +
                        "\"rpm\":%d, \"fan_speed\":%s, \"bypass_open\":%s, " +
                        "\"commanded_speed\":%s, \"supply_duty\":%s}",
                        ts, humidity, tempSupply, tempSupply, tempOutside, tempExhaust, tempExtract, rpm,
                        fanSpeed, bypassOpen, commandedSpeed, supplyDuty
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
                    + "fan_rpm, fan_speed_level, bypass_open, commanded_speed, supply_duty";
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
        HistoricalBaselines baselines = loadHistoricalBaselines(Instant.now());
        PollResult result;
        synchronized (clientLock) {
            result = pollWithFreshConnection(baselines);
        }
        if (result == null) {
            return;
        }

        persistControlState(controlStateSnapshot());

        if (saveToDatabase(result.humidity(), result.tempSupply(), result.tempOutside(), result.tempExhaust(),
            result.tempExtract(), result.supplyRpm(), result.observedFanSpeed(), result.bypassState(),
            result.commandedFanSpeed(), result.supplyDuty())) {
            log("Logged: Humidity=" + result.humidity() + "%, Temp=" + result.tempSupply() + "C, RPM="
                    + result.supplyRpm() + (result.boostActive() ? " [BOOST ACTIVE]" : "")
                    + defrostStatusSuffix(result.defrostState()));
        } else {
            log("Read (Not Logged): Humidity=" + result.humidity() + "%, Temp=" + result.tempSupply()
                    + "C, RPM=" + result.supplyRpm() + (result.boostActive() ? " [BOOST ACTIVE]" : "")
                    + defrostStatusSuffix(result.defrostState()));
        }

        publishHomeAssistant(result);
    }

    private PollResult pollWithFreshConnection(HistoricalBaselines baselines) {
        try {
            client.disconnect();
            log("Establishing connection to Genvex...");
            client.connect();

            int humidity = client.readDatapoint(26);
            int tempSupplyRaw = client.readDatapoint(20);
            int supplyRpm = client.readDatapoint(35);
            int supplyDuty = client.readDatapoint(18);

            if (humidity == -1 || tempSupplyRaw == -1 || supplyRpm == -1 || supplyDuty == -1) {
                throw new IOException("Required datapoint is unavailable");
            }

            int tempOutsideRaw = readOptionalDatapoint(21, "Outside temperature");
            int tempExhaustRaw = readOptionalDatapoint(22, "Exhaust temperature");
            int tempExtractRaw = readOptionalDatapoint(23, "Extract temperature");
            int extractRpm = readOptionalDatapoint(36, "Extract fan RPM", 2);

            int tempSensorOffsetRaw = Integer.parseInt(System.getenv().getOrDefault("TEMP_SUPPLY_OFFSET_RAW", "-300"));
            double tempSupply = rawTemperature(tempSupplyRaw, tempSensorOffsetRaw);
            double tempOutside = rawTemperature(tempOutsideRaw, tempSensorOffsetRaw);
            double tempExhaust = rawTemperature(tempExhaustRaw, tempSensorOffsetRaw);
            double tempExtract = rawTemperature(tempExtractRaw, tempSensorOffsetRaw);

            // Runs after the temperature conversions so the recovery test can convert this poll's humidity
            // to a mixing ratio with this poll's indoor temperature rather than the previous poll's.
            checkBoostLogic(humidity, baselines, tempExtract);

            DefrostState defrostState = detectDefrostState(supplyRpm, extractRpm, tempSupply);
            boolean isDefrosting = defrostState != DefrostState.INACTIVE;
            if (defrostState == DefrostState.ACTIVE) {
                log("STATUS: Unit appears to be in DEFROST/ANTI-ICE mode (Supply Off, Extract On, Low Temp).");
            } else if (defrostState == DefrostState.UNKNOWN) {
                log("STATUS: Defrost state unknown (Supply Off, Low Temp, Extract RPM unavailable). Fan writes paused.");
            }

            int observedFanSpeed = estimateFanSpeed(supplyRpm, supplyDuty);
            int setpointReadback = readFanSetpoint();
            if (commandedFanSpeed == -1) {
                // After a restart the unit's own setpoint is what we are actually tracking; the
                // duty-derived speed is only the fallback when the read-back is unavailable.
                commandedFanSpeed = setpointReadback >= 0 ? setpointReadback : observedFanSpeed;
            }
            if (policyTargetSpeed == -1) {
                policyTargetSpeed = commandedFanSpeed;
            }

            int bypassState = -1;
            try {
                bypassState = normalizeBypassState(client.readDatapoint(53));
            } catch (IOException e) {
                logError("Bypass status unavailable: " + e.getMessage());
            }
            int effectiveBypassState = effectiveBypassState(bypassState);

            // Apply Fan Speed Control
            updateFanSpeed(humidity, tempSupply, tempOutside, tempExtract, observedFanSpeed, supplyDuty,
                    isDefrosting, effectiveBypassState, setpointReadback);

            log("Polled Data: Humidity=" + humidity + "%, SupplyTempRaw=" + tempSupplyRaw
                + ", OutsideTempRaw=" + tempOutsideRaw + ", ExhaustTempRaw=" + tempExhaustRaw
                + ", ExtractTempRaw=" + tempExtractRaw
                + ", SupplyRPM=" + supplyRpm + ", SupplyDuty=" + supplyDuty + ", ExtractRPM=" + extractRpm
                + ", Bypass=" + bypassStateLabel(bypassState)
                + ", FanSetpoint=" + fanSetpointLabel(setpointReadback));

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
                    observedFanSpeed, bypassState, boostActive, defrostState, commandedFanSpeed, supplyDuty);

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
            DefrostState defrostState, int commandedFanSpeed, int supplyDuty) {}

    /**
     * Address 24 is the fan-speed setpoint this service writes. Reading it back tells us whether the
     * unit actually kept the setpoint, which is the only way to tell "the unit reverted our write"
     * apart from "the unit kept the setpoint but is running a different duty". The read is optional:
     * some firmware revisions do not expose it, so failures degrade to the duty-derived estimate.
     */
    private int readFanSetpoint() throws InterruptedException {
        try {
            int value = normalizeFanSetpoint(client.readDatapoint(24, 1));
            if (value >= 0 && !setpointReadbackAvailable) {
                setpointReadbackAvailable = true;
                log("Fan speed setpoint read-back (address 24) is available on this firmware.");
            }
            return value;
        } catch (IOException e) {
            if (!setpointReadbackUnavailableLogged) {
                setpointReadbackUnavailableLogged = true;
                logError("Fan speed setpoint read-back unavailable; falling back to the duty-derived"
                        + " fan speed: " + e.getMessage());
            }
            return -1;
        }
    }

    static int normalizeFanSetpoint(int rawValue) {
        return rawValue >= 0 && rawValue <= 4 ? rawValue : -1;
    }

    private static String fanSetpointLabel(int setpointReadback) {
        return setpointReadback < 0 ? "unknown" : String.valueOf(setpointReadback);
    }

    /**
     * A single failed bypass read used to cancel evening cooling outright, which reset the cooling
     * baseline and dropped the fan a step. Reuse the last known state for a few polls instead.
     */
    private int effectiveBypassState(int bypassState) {
        if (bypassState >= 0) {
            unknownBypassReads = 0;
            lastKnownBypassState = bypassState;
            return bypassState;
        }
        unknownBypassReads++;
        if (unknownBypassReads <= BYPASS_UNKNOWN_TOLERANCE && lastKnownBypassState >= 0) {
            log("Bypass state unavailable; reusing last known state ("
                    + bypassStateLabel(lastKnownBypassState) + ") for control.");
            return lastKnownBypassState;
        }
        return -1;
    }

    private int readOptionalDatapoint(int address, String label) throws InterruptedException {
        return readOptionalDatapoint(address, label, 1);
    }

    private int readOptionalDatapoint(int address, String label, int retries) throws InterruptedException {
        try {
            return client.readDatapoint(address, retries);
        } catch (IOException e) {
            logError(label + " unavailable: " + e.getMessage());
            return -1;
        }
    }

    enum DefrostState {
        INACTIVE,
        ACTIVE,
        UNKNOWN
    }

    static DefrostState detectDefrostState(int supplyRpm, int extractRpm, double supplyTemp) {
        if (supplyRpm >= 100 || supplyTemp >= 10.0) {
            return DefrostState.INACTIVE;
        }
        if (extractRpm < 0) {
            return DefrostState.UNKNOWN;
        }
        return extractRpm > 500 ? DefrostState.ACTIVE : DefrostState.INACTIVE;
    }

    static String defrostStatusSuffix(DefrostState state) {
        return switch (state) {
            case ACTIVE -> " [DEFROSTING]";
            case UNKNOWN -> " [DEFROST UNKNOWN]";
            case INACTIVE -> "";
        };
    }

    private ControlState controlStateSnapshot() {
        synchronized (clientLock) {
            return new ControlState(boostActive, boostBaselineHumidity, boostEndTime);
        }
    }

    private void restoreControlState(ControlState state) {
        ControlState restored = restorableControlState(BOOST_ENABLED, state);
        boostActive = restored.boostActive();
        boostBaselineHumidity = restored.boostBaseline();
        boostEndTime = restored.boostEnd();
        if (boostActive) {
            log(String.format(Locale.ROOT,
                "Restored humidity recovery toward %.1f%% after restart.",
                    humidityRecoveryTarget(boostBaselineHumidity)));
        }
    }

    static ControlState restorableControlState(boolean boostEnabled, ControlState state) {
        if (boostEnabled && state.boostActive() && Double.isFinite(state.boostBaseline())) {
            return new ControlState(true, state.boostBaseline(), 0);
        }
        return new ControlState(false, Double.NaN, 0);
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
                policyTargetSpeed = speed;
                lastFanCommandTime = System.currentTimeMillis();
                fanCommandAttempts = 0;
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
            int observedFanSpeed, int supplyDuty, boolean isDefrosting, int bypassState,
            int setpointReadback) {
        if (restartInProgress.get()) {
            resetHeatLossGuard();
            log("Maintenance restart active. Automatic fan control paused.");
            return;
        }
        if (monitorOnly) {
            resetEveningCooling();
            resetHeatLossGuard();
            log("Monitor mode active. Recommended speed: " + NORMAL_SPEED + " (Reason: Monitor Only)");
            return;
        }

        int targetSpeed = NORMAL_SPEED;
        String reason = "Normal";
        LocalTime now = LocalTime.now();
        long nowMillis = System.currentTimeMillis();
        boolean isNightTime = isNight(now);
        boolean automaticControl = true;
        // Declared out here because the heat-loss guard needs it as its exemption floor. The
        // selectEveningCoolingSpeed calls stay inside their own branches: that method starts and stops
        // evening cooling and logs, so calling it on the manual, static or very-high paths - which today
        // only call resetEveningCooling() - would change behaviour.
        int coolingSpeed = 0;

        if (manualOverrideActive && nowMillis >= manualOverrideEndTime) {
            manualOverrideActive = false;
            manualOverrideSpeed = -1;
        }

        // Manual override takes precedence over everything
        if (manualOverrideActive && nowMillis < manualOverrideEndTime) {
            resetEveningCooling();
            automaticControl = false;
            targetSpeed = manualOverrideSpeed;
            reason = "Manual Override";
        } else if (staticRpmMode) {
            resetEveningCooling();
            automaticControl = false;
            targetSpeed = staticRpmSpeed;
            reason = "Static RPM Mode";
        } else if (boostActive) {
            coolingSpeed = selectEveningCoolingSpeed(tempSupply, tempOutside, tempExtract, bypassState, now);
            targetSpeed = selectHumidityRecoverySpeed(humidity, HUMIDITY_POLICY, coolingSpeed,
                    policyTargetSpeed, HUMIDITY_HYSTERESIS);
            reason = String.format(Locale.ROOT, coolingSpeed > 0
                ? "Shower Boost + Evening Cooling (delta %.1f%%)"
                : "Shower Boost (delta %.1f%%)", humidity - boostBaselineHumidity);
        } else {
            int veryHighSpeed = Math.max(3, NORMAL_SPEED);
            int effectiveVeryHigh = effectiveThreshold(HUMIDITY_VERY_HIGH_THRESHOLD, HUMIDITY_HYSTERESIS,
                    policyTargetSpeed >= veryHighSpeed);
            if (humidity >= effectiveVeryHigh) {
                resetEveningCooling();
                targetSpeed = veryHighSpeed;
                reason = "Humidity Very High";
            } else {
                coolingSpeed = selectEveningCoolingSpeed(tempSupply, tempOutside, tempExtract, bypassState, now);
                targetSpeed = selectAutomaticSpeed(humidity, isNightTime, coolingSpeed,
                        HUMIDITY_LOW_THRESHOLD, HUMIDITY_HIGH_THRESHOLD, NORMAL_SPEED,
                        policyTargetSpeed, HUMIDITY_HYSTERESIS);
                int effectiveHigh = effectiveThreshold(HUMIDITY_HIGH_THRESHOLD, HUMIDITY_HYSTERESIS,
                        policyTargetSpeed >= Math.max(2, NORMAL_SPEED));
                reason = coolingSpeed > 0 ? "Evening Cooling"
                        : humidity >= effectiveHigh ? "Humidity High"
                        : isNightTime ? "Night Mode"
                        : humidity <= HUMIDITY_LOW_THRESHOLD ? "Humidity Low" : "Normal";
            }
        }

        // What humidity control asked for, before any limiter touched it. This is the guard's input and the
        // hysteresis latch for the next poll; the guard's own reduced write must never take its place.
        int policyTarget = targetSpeed;
        policyTargetSpeed = automaticControl ? policyTarget : commandedFanSpeed;
        if (automaticControl) {
            double moisture = HumidityPhysics.mixingRatioGramsPerKg(humidity, tempExtract);
            HeatLossGuardPolicy.HeatLossState previousHeatLossState = heatLossState;
            heatLossState = HeatLossGuardPolicy.evaluate(previousHeatLossState, policyTarget, humidity,
                    moisture, tempExtract, tempOutside, nowMillis, HEAT_LOSS_CONFIG);
            int guardedSpeed = HeatLossGuardPolicy.guardedSpeed(policyTarget, coolingSpeed, heatLossState,
                    HEAT_LOSS_CONFIG);
            if (guardedSpeed < targetSpeed) {
                targetSpeed = guardedSpeed;
                reason += " + Heat Loss Guard";
            }
            logHeatLossTransition(previousHeatLossState, heatLossState, moisture, tempExtract, tempOutside,
                    nowMillis);
        } else {
            heatLossState = HeatLossGuardPolicy.HeatLossState.IDLE;
        }

        int unrestrictedTargetSpeed = targetSpeed;
        if (automaticControl) {
            targetSpeed = limitNightSpeed(targetSpeed, isNightTime, humidity,
                    boostBaselineHumidity, boostActive, HUMIDITY_POLICY);
            if (targetSpeed < unrestrictedTargetSpeed) {
                reason += " + Night Noise Limit";
            }
        }
        
        // If we are in Defrost mode, the unit will override our setting (making RPM 0), so sending
        // commands is futile. The controller keeps the last setpoint we wrote, so it returns to that
        // speed once defrost ends.

        // Supply duty tells us what the controller is *trying* to do. Duty 0 outside defrost means
        // the controller believes the fan should be off, so the setpoint has not taken effect.
        boolean fanStopped = targetSpeed > 0 && supplyDuty == 0 && !isDefrosting;
        boolean held = setpointHeld(commandedFanSpeed, observedFanSpeed, setpointReadback);
        if (held && !fanStopped && fanCommandAttempts > 0) {
            log("Genvex is holding fan setpoint " + commandedFanSpeed + " again after "
                    + fanCommandAttempts + " attempt(s).");
            fanCommandAttempts = 0;
        }

        FanCommandDecision decision = decideFanCommand(targetSpeed != commandedFanSpeed,
                targetSpeed > commandedFanSpeed, held && !fanStopped, nowMillis, lastFanCommandTime,
                fanCommandAttempts, FAN_PACING);

        // Defrost is decided last so the diagnostic line still reports one outcome per poll: the
        // controller drives the fan itself while defrosting, and it keeps our setpoint for afterwards.
        String outcome = isDefrosting ? "hold (defrost active)"
                : decision.send() ? "write"
                : "wait " + (decision.waitMillis() / 1000) + "s";
        log(String.format(Locale.ROOT,
            "Fan decision: target=%d (%s) commanded=%d observed=%d duty=%d%% setpoint=%s settled=%b"
            + " attempts=%d guard=%s -> %s",
            targetSpeed, reason, commandedFanSpeed, observedFanSpeed, supplyDuty / 100,
            fanSetpointLabel(setpointReadback), held && !fanStopped, fanCommandAttempts,
            HeatLossGuardPolicy.describe(heatLossState, nowMillis), outcome));

        if (isDefrosting || !decision.send()) {
            return;
        }
        if (fanStopped) {
            log("Fan duty is 0 (OFF) but target is " + targetSpeed + ". Re-applying setpoint.");
        }
        try {
            log("Adjusting Fan Speed: " + observedFanSpeed + " -> " + targetSpeed + " (Reason: " + reason
                    + ", attempt " + decision.attempts() + ")");
            client.setFanSpeed(targetSpeed);
            commandedFanSpeed = targetSpeed;
        } catch (Exception e) {
            logError("Failed to set fan speed: " + e.getMessage());
        } finally {
            // Record the attempt even when the write failed, so a broken link is paced the same way
            // as a rejected setpoint instead of being retried on every poll.
            lastFanCommandTime = nowMillis;
            fanCommandAttempts = decision.attempts();
        }

        if (fanCommandAttempts > FAN_PACING.attemptsBeforeBackoff()) {
            log(String.format(Locale.ROOT,
                "Genvex is not holding fan setpoint %d (observed %d, duty %d%%, setpoint read-back %s)"
                + " after %d attempts. Backing off; next attempt in %d min.",
                targetSpeed, observedFanSpeed, supplyDuty / 100, fanSetpointLabel(setpointReadback),
                fanCommandAttempts, retryIntervalMillis(fanCommandAttempts, FAN_PACING) / 60_000));
        }
    }

    /**
     * Humidity is reported as a whole percent and jitters by a point or two, so a bare threshold
     * comparison flips the target speed on every poll whenever the reading sits on a boundary.
     * Once a threshold has raised the fan, hold that step until humidity falls a full deadband below
     * the threshold. This mirrors the start/continue deadband {@link EveningCoolingPolicy} already
     * applies to temperatures.
     */
    static int effectiveThreshold(int threshold, int hysteresis, boolean alreadyAbove) {
        return alreadyAbove ? threshold - hysteresis : threshold;
    }

    static int selectHumiditySpeed(int humidity, int lowThreshold, int highThreshold, int normalSpeed,
            int currentSpeed, int hysteresis) {
        int highSpeed = Math.max(2, normalSpeed);
        if (humidity >= effectiveThreshold(highThreshold, hysteresis, currentSpeed >= highSpeed)) {
            return highSpeed;
        }
        // The low branch lowers the fan, so its deadband widens upward instead of downward.
        if (humidity <= (currentSpeed <= 1 ? lowThreshold + hysteresis : lowThreshold)) {
            return 1;
        }
        return normalSpeed;
    }

    static int selectAutomaticSpeed(int humidity, boolean night, int coolingSpeed,
            int lowThreshold, int highThreshold, int normalSpeed, int currentSpeed, int hysteresis) {
        int highSpeed = Math.max(2, normalSpeed);
        boolean aboveHigh = humidity
                >= effectiveThreshold(highThreshold, hysteresis, currentSpeed >= highSpeed);
        if (coolingSpeed > 0) {
            return aboveHigh ? Math.max(coolingSpeed, highSpeed) : coolingSpeed;
        }
        if (aboveHigh) {
            return highSpeed;
        }
        if (night) {
            return 1;
        }
        return selectHumiditySpeed(humidity, lowThreshold, highThreshold, normalSpeed, currentSpeed,
                hysteresis);
    }

    static int limitNightSpeed(int targetSpeed, boolean night, int humidity,
            double baselineHumidity, boolean showerBoostActive, HumidityPolicy policy) {
        if (showerBoostActive || !night || hasHumidityRise(humidity, baselineHumidity, policy)) {
            return targetSpeed;
        }
        return Math.min(2, targetSpeed);
    }

    record FanCommandPacing(long minIntervalMillis, long retryIntervalMillis,
            long maxRetryIntervalMillis, int attemptsBeforeBackoff) {}

    record FanCommandDecision(boolean send, int attempts, long waitMillis) {}

    /**
     * Decides whether to write the fan setpoint on this poll.
     *
     * <p>The unit's own controller can revert a setpoint we wrote (week program, its own humidity
     * control, a panel change). Re-writing on a fixed interval then fights it forever, which is what
     * makes the fan cycle between two speeds all night. Instead, count consecutive unsettled polls
     * and back off exponentially up to {@code maxRetryIntervalMillis}, so the fan comes to rest at
     * whatever the unit insists on until conditions actually change.
     *
     * @param targetChanged the policy target differs from the setpoint we last wrote
     * @param raising the new target is higher than what we last wrote
     * @param settled the unit is running the setpoint we last wrote
     */
    static FanCommandDecision decideFanCommand(boolean targetChanged, boolean raising, boolean settled,
            long now, long lastCommandTime, int attempts, FanCommandPacing pacing) {
        long elapsed = Math.max(0, now - lastCommandTime);
        if (targetChanged) {
            // Raising speed answers humidity or heat, so it goes out immediately. Lowering is never
            // urgent, so it waits out the minimum spacing and cannot chatter against a raise.
            long required = raising ? 0 : pacing.minIntervalMillis();
            return elapsed >= required
                    ? new FanCommandDecision(true, 0, 0)
                    : new FanCommandDecision(false, attempts, required - elapsed);
        }
        if (settled) {
            return new FanCommandDecision(false, 0, 0);
        }
        long required = Math.max(pacing.minIntervalMillis(), retryIntervalMillis(attempts, pacing));
        return elapsed >= required
                ? new FanCommandDecision(true, attempts + 1, 0)
                : new FanCommandDecision(false, attempts, required - elapsed);
    }

    static long retryIntervalMillis(int attempts, FanCommandPacing pacing) {
        long interval = pacing.retryIntervalMillis();
        int doublings = Math.max(0, attempts - pacing.attemptsBeforeBackoff() + 1);
        for (int i = 0; i < doublings && interval < pacing.maxRetryIntervalMillis(); i++) {
            interval = Math.min(pacing.maxRetryIntervalMillis(), interval * 2);
        }
        return interval;
    }

    /**
     * Whether the unit is running the speed we last commanded. The address 24 read-back is
     * authoritative when the firmware exposes it; otherwise fall back to the duty-derived estimate.
     */
    static boolean setpointHeld(int commandedSpeed, int observedFanSpeed, int setpointReadback) {
        if (commandedSpeed < 0) {
            return false;
        }
        if (setpointReadback >= 0) {
            return setpointReadback == commandedSpeed;
        }
        return observedFanSpeed == commandedSpeed;
    }

    record HumidityPolicy(int riseThreshold, int recoveryTolerance, int boostSpeed,
            int normalSpeed, int lowThreshold, int highThreshold, int veryHighThreshold) {}

    static int selectHumidityRecoverySpeed(int humidity, HumidityPolicy policy, int coolingSpeed,
            int currentSpeed, int hysteresis) {
        int boostSpeed = Math.max(policy.normalSpeed(), policy.boostSpeed());
        int veryHighSpeed = Math.max(3, policy.normalSpeed());
        int absoluteHumiditySpeed = humidity >= effectiveThreshold(policy.veryHighThreshold(),
                hysteresis, currentSpeed >= veryHighSpeed)
            ? veryHighSpeed
            : selectHumiditySpeed(humidity, policy.lowThreshold(), policy.highThreshold(),
                policy.normalSpeed(), currentSpeed, hysteresis);
        return Math.max(coolingSpeed, Math.max(boostSpeed, absoluteHumiditySpeed));
    }

    static boolean hasHumidityRise(int humidity, double baselineHumidity, HumidityPolicy policy) {
        return Double.isFinite(baselineHumidity)
            && humidity - baselineHumidity >= policy.riseThreshold();
    }

    static double humidityRecoveryTarget(double baselineHumidity) {
        return baselineHumidity;
    }

    /**
     * Whether the house has recovered from the shower. Relative humidity is temperature-dependent, so a
     * house that has cooled a degree since the shower started reads about four points wetter than it is,
     * and in winter that keeps the boost running for hours; the mixing ratio is the honest comparison.
     *
     * <p>It <em>falls back</em> to the relative-humidity baseline rather than replacing it. The persisted
     * {@code boost_baseline} column stays a percentage so that an older binary reading this database still
     * behaves, which means a boost restored across a restart has only the RH baseline to work with.
     */
    static boolean shouldDeactivateBoost(int humidity, double baselineHumidity,
            double moistureGramsPerKg, double baselineMoistureGramsPerKg) {
        if (Double.isFinite(baselineMoistureGramsPerKg) && Double.isFinite(moistureGramsPerKg)) {
            return moistureGramsPerKg <= baselineMoistureGramsPerKg;
        }
        return Double.isFinite(baselineHumidity)
            && humidity <= humidityRecoveryTarget(baselineHumidity);
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

    /**
     * Average indoor mixing ratio over the window, in g/kg. The conversion happens per row in Java because
     * SQLite has no saturation-pressure function, and averaging the humidity column first would defeat the
     * point: the rows can span a temperature change, which is exactly what this baseline has to see through.
     *
     * @return NaN when no row in the window carries both a usable humidity and a usable extract
     *         temperature, so the caller can fall back to the relative-humidity baseline
     */
    static double historicalMoistureAverage(Connection connection, Instant endExclusive,
            int windowMinutes) throws SQLException {
        String sql = "SELECT humidity, temp_extract FROM humidity_readings "
                + "WHERE timestamp >= ? AND timestamp < ?";
        DateTimeFormatter sqliteTimestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sqliteTimestamp.format(endExclusive.minusSeconds(windowMinutes * 60L)));
            statement.setString(2, sqliteTimestamp.format(endExclusive));
            double total = 0.0;
            int samples = 0;
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    int humidity = result.getInt(1);
                    if (result.wasNull()) {
                        continue;
                    }
                    double tempExtract = result.getDouble(2);
                    if (result.wasNull()) {
                        continue;
                    }
                    double moisture = HumidityPhysics.mixingRatioGramsPerKg(humidity, tempExtract);
                    if (!Double.isFinite(moisture)) {
                        continue;
                    }
                    total += moisture;
                    samples++;
                }
            }
            return samples == 0 ? Double.NaN : total / samples;
        }
    }

    private int selectEveningCoolingSpeed(double tempSupply, double tempOutside, double tempExtract,
            int bypassState, LocalTime now) {
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
                COOLING_STOP_TEMP, COOLING_START_TEMP, COOLING_MIN_SUPPLY_TEMP,
                bypassState == 1, stalled);

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

    /**
     * Called whenever automatic control is suspended. A step-down is only ever justified against the policy
     * target it was measured from, so returning to automatic control must never resume one measured against
     * a target that no longer applies.
     */
    private void resetHeatLossGuard() {
        heatLossState = HeatLossGuardPolicy.HeatLossState.IDLE;
        policyTargetSpeed = commandedFanSpeed;
    }

    /** One line per state change, not per poll - the per-poll fan decision line already carries the state. */
    private void logHeatLossTransition(HeatLossGuardPolicy.HeatLossState previous,
            HeatLossGuardPolicy.HeatLossState next, double moistureGramsPerKg, double tempExtract,
            double tempOutside, long nowMillis) {
        String event = HeatLossGuardPolicy.describeTransition(previous, next, nowMillis);
        if (event == null) {
            return;
        }
        log(String.format(Locale.ROOT,
                "Heat loss guard: %s (%s, indoor %.1fC, outside %.1fC, moisture %.2f g/kg)",
                event, HeatLossGuardPolicy.describe(next, nowMillis), tempExtract, tempOutside,
                moistureGramsPerKg));
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

            synchronized (clientLock) {
                if (monitorOnly) {
                    sendError(t, 409, "Disable monitor-only mode before restarting the unit");
                    return;
                }
                if (!restartInProgress.compareAndSet(false, true)) {
                    sendError(t, 409, "A restart is already in progress");
                    return;
                }
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
                } finally {
                    restartInProgress.set(false);
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
                            if (restartInProgress.get()) {
                                sendError(t, 409, "Control mode cannot change during a restart");
                                return;
                            }
                            monitorOnly = requestedMonitorOnly;
                            if (requestedMonitorOnly) {
                                staticRpmMode = false;
                                manualOverrideActive = false;
                                manualOverrideSpeed = -1;
                                manualOverrideEndTime = 0;
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

    private void checkBoostLogic(int currentHumidity, HistoricalBaselines baselines, double tempExtract) {
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

            double baselineHumidity = Double.isFinite(baselines.humidityAverage())
                    ? baselines.humidityAverage() : lastHumidity;
            if (hasHumidityRise(currentHumidity, baselineHumidity, HUMIDITY_POLICY)) {
                log(String.format(Locale.ROOT,
                        "Humidity rise detected (%d%% current, pre-rise baseline %.1f%%). Activating boost.",
                        currentHumidity, baselineHumidity));
                activateBoost(currentHumidity, baselineHumidity, baselines.moistureAverage(), tempExtract);
            }
        } else {
            double moisture = HumidityPhysics.mixingRatioGramsPerKg(currentHumidity, tempExtract);
            if (shouldDeactivateBoost(currentHumidity, boostBaselineHumidity, moisture,
                    boostBaselineMoisture)) {
                boolean judgedOnMoisture =
                        Double.isFinite(boostBaselineMoisture) && Double.isFinite(moisture);
                log(judgedOnMoisture
                        ? String.format(Locale.ROOT,
                            "Humidity recovered (%.2f g/kg moisture at %d%%, pre-shower baseline"
                            + " %.2f g/kg). Deactivating Boost.",
                            moisture, currentHumidity, boostBaselineMoisture)
                        : String.format(Locale.ROOT,
                            "Humidity recovered (%d%%, recovery target %.1f%%). Deactivating Boost.",
                            currentHumidity, humidityRecoveryTarget(boostBaselineHumidity)));
                deactivateBoost();
            }
        }
    }

    private void activateBoost(int activationHumidity, double baselineHumidity,
            double baselineMoisture, double tempExtract) {
        boostActive = true;
        boostBaselineHumidity = baselineHumidity;
        // Not persisted: the boost_baseline column stays a relative humidity so an older binary can still
        // read this database. When the baseline window holds no usable row, the previous poll's humidity is
        // the best pre-rise stand-in available; if that cannot be converted either the exit falls back to RH.
        boostBaselineMoisture = Double.isFinite(baselineMoisture)
                ? baselineMoisture
                : HumidityPhysics.mixingRatioGramsPerKg(lastHumidity, tempExtract);
        boostEndTime = 0;
        log(Double.isFinite(boostBaselineMoisture)
                ? String.format(Locale.ROOT,
                    "Shower boost activated at %d%% humidity; maintaining boost until moisture returns to"
                    + " %.2f g/kg (pre-rise baseline %.1f%% RH).",
                    activationHumidity, boostBaselineMoisture, baselineHumidity)
                : String.format(Locale.ROOT,
                    "Shower boost activated at %d%% humidity; maintaining boost until humidity returns to %.1f%%.",
                    activationHumidity, baselineHumidity));
    }

    private void deactivateBoost() {
        boostActive = false;
        boostBaselineHumidity = Double.NaN;
        boostBaselineMoisture = Double.NaN;
        boostEndTime = 0;
        // Speed change will be handled by updateFanSpeed()
    }

    /** The pre-rise baselines, both averaged over the same window: relative humidity and mixing ratio. */
    private record HistoricalBaselines(double humidityAverage, double moistureAverage) {}

    private HistoricalBaselines loadHistoricalBaselines(Instant endExclusive) {
        try (Connection connection = DriverManager.getConnection(DB_URL)) {
            return new HistoricalBaselines(
                    historicalHumidityAverage(connection, endExclusive, HUMIDITY_BASELINE_MINUTES),
                    historicalMoistureAverage(connection, endExclusive, HUMIDITY_BASELINE_MINUTES));
        } catch (SQLException e) {
            logError("Failed to load historical humidity baselines: " + e.getMessage());
            return new HistoricalBaselines(Double.NaN, Double.NaN);
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
                sendError(t, 400, "level and duration_minutes must be integers");
                return;
            }

            if (level < 0 || level > 4) {
                sendError(t, 400, "level must be an integer from 0 to 4");
                return;
            }
            if (durationMinutes < 1 || durationMinutes > MAX_MANUAL_OVERRIDE_MINUTES) {
                sendError(t, 400, "duration_minutes must be from 1 to " + MAX_MANUAL_OVERRIDE_MINUTES);
                return;
            }

            try {
                synchronized (clientLock) {
                    if (restartInProgress.get()) {
                        sendError(t, 409, "Fan control is unavailable during a restart");
                        return;
                    }
                    if (monitorOnly) {
                        sendError(t, 409, "Disable monitor-only mode before controlling the fan");
                        return;
                    }
                    setFanSpeedImmediately(level);
                    manualOverrideActive = true;
                    manualOverrideSpeed = level;
                    manualOverrideEndTime = System.currentTimeMillis() + (durationMinutes * 60L * 1000L);
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
                        if (restartInProgress.get()) {
                            sendError(t, 409, "Fan control is unavailable during a restart");
                            return;
                        }
                        if (requestedEnabled) {
                            if (monitorOnly) {
                                sendError(t, 409, "Disable monitor-only mode before controlling the fan");
                                return;
                            }
                            setFanSpeedImmediately(requestedSpeed);
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
            double tempExtract, int rpm, int fanSpeed, int bypassState, int commandedSpeed, int supplyDuty) {
        String sql = "INSERT INTO humidity_readings (humidity, temp_supply, temp_outside, temp_exhaust, " +
                     "temp_extract, fan_rpm, fan_speed_level, bypass_open, commanded_speed, supply_duty) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

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
            setNullableInteger(pstmt, 9, commandedSpeed);
            setNullableInteger(pstmt, 10, supplyDuty);
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

    /** Supply-fan duty percentage per speed on the Optima 270, see ADDRESS_MAP.md. */
    private static final int[] SPEED_DUTY_PERCENT = {0, 30, 50, 70, 100};

    /**
     * Maps duty to the nearest documented speed rather than using hard upper bounds. The old bounds
     * were asymmetric, so a speed-2 duty of exactly 60% read as speed 3 and the control loop then
     * saw a permanent mismatch against its own setpoint.
     */
    static int estimateFanSpeed(int rpm, int duty) {
        if (rpm < 100) {
            return 0;
        }
        int pct = duty / 100; // e.g. 5000 -> 50
        if (pct < 15) return 0;
        int nearest = 1;
        for (int speed = 2; speed < SPEED_DUTY_PERCENT.length; speed++) {
            if (Math.abs(pct - SPEED_DUTY_PERCENT[speed]) < Math.abs(pct - SPEED_DUTY_PERCENT[nearest])) {
                nearest = speed;
            }
        }
        return nearest;
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
