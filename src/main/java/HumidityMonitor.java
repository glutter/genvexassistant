import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
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
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final String sessionId = java.util.UUID.randomUUID().toString().substring(0, 6);

    // Configuration
    private static final int POLL_INTERVAL = Integer.parseInt(System.getenv().getOrDefault("POLL_INTERVAL", "30"));
    private volatile boolean monitorOnly = Boolean.parseBoolean(System.getenv().getOrDefault("MONITOR_ONLY", "true"));

    // Boost Configuration
    private static final boolean BOOST_ENABLED = Boolean.parseBoolean(System.getenv().getOrDefault("BOOST_ENABLED", "true"));
    private static final int HUMIDITY_RISE_THRESHOLD = Integer.parseInt(System.getenv().getOrDefault("HUMIDITY_RISE_THRESHOLD", "3")); // % rise per poll
    private static final int BOOST_SPEED = Integer.parseInt(System.getenv().getOrDefault("BOOST_SPEED", "3"));
    private static final int NORMAL_SPEED = Integer.parseInt(System.getenv().getOrDefault("NORMAL_SPEED", "2"));
    private static final long BOOST_DURATION_MS = Integer.parseInt(System.getenv().getOrDefault("BOOST_DURATION_MINUTES", "30")) * 60 * 1000L;
    private static final int HUMIDITY_HYSTERESIS = Integer.parseInt(System.getenv().getOrDefault("HUMIDITY_HYSTERESIS", "5")); // % below target to exit boost

    // General Control Configuration
    private static final int HUMIDITY_VERY_HIGH_THRESHOLD = Integer.parseInt(System.getenv().getOrDefault("HUMIDITY_VERY_HIGH_THRESHOLD", "80"));
    private static final int HUMIDITY_HIGH_THRESHOLD = Integer.parseInt(System.getenv().getOrDefault("HUMIDITY_HIGH_THRESHOLD", "65"));
    private static final LocalTime NIGHT_START = LocalTime.parse(System.getenv().getOrDefault("NIGHT_START", "23:00"));
    private static final LocalTime NIGHT_END = LocalTime.parse(System.getenv().getOrDefault("NIGHT_END", "06:30"));

    // State
    private int lastHumidity = -1;
    private long lastHumidityTime = 0;
    private double lastTemp = -1.0;
    private int lastRpm = -1;
    private boolean boostActive = false;
    private long boostEndTime = 0;
    private long boostMinEndTime = 0; // Minimum boost duration before allowing deactivation
    private int boostActivationHumidity = -1; // Humidity level when boost was activated
    private int currentFanSpeed = -1;
    private int dbErrorCount = 0;
    // Manual override (Udluftning)
    private volatile boolean manualOverrideActive = false;
    private volatile long manualOverrideEndTime = 0;
    private volatile int manualOverrideSpeed = -1;
    // Static RPM Mode
    private volatile boolean staticRpmMode = false;
    private volatile int staticRpmSpeed = 2;

    public HumidityMonitor(String ip, String email) {
        this.client = new GenvexClient(ip, email);
    }

    public void start() {
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

    private void initializeDatabase() {
        String sql = "CREATE TABLE IF NOT EXISTS humidity_readings (" +
                     "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                     "humidity INTEGER, " +
                     "temp_supply REAL, " +
                     "fan_rpm INTEGER)";
        
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            log("Database initialized at " + DB_PATH);
        } catch (SQLException e) {
            logError("Failed to initialize database: " + e.getMessage());
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
            // Effective speed: if RPM is 0, the fan is effectively off regardless of command
            int effectiveSpeed = (lastRpm < 100) ? 0 : currentFanSpeed;
            
            long now = System.currentTimeMillis();
            long manualOverrideSecsLeft = Math.max(0, (manualOverrideEndTime - now) / 1000);
            long boostSecsLeft = Math.max(0, (boostEndTime - now) / 1000);
            boolean isManualOverrideCurrentlyActive = manualOverrideActive && (now < manualOverrideEndTime);

            String json = String.format(
                "{\"humidity\":%d, \"temp\":%.1f, \"rpm\":%d, \"fan_speed\":%d, \"commanded_speed\":%d, \"boost\":%b, \"static_mode\":%b, \"static_speed\":%d, \"monitor_only\":%b, \"manual_override_active\":%b, \"manual_override_secs_left\":%d, \"boost_secs_left\":%d}",
                lastHumidity, lastTemp, lastRpm, effectiveSpeed, currentFanSpeed, boostActive, staticRpmMode, staticRpmSpeed, monitorOnly, isManualOverrideCurrentlyActive, manualOverrideSecsLeft, boostSecsLeft
            );
            sendJson(t, json);
        }
    }

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
            int step = 1;

            switch (range) {
                case "week":
                    timeFilter = "-7 days";
                    step = 20; // Downsample: ~10 min interval (assuming 30s poll)
                    break;
                case "month":
                    timeFilter = "-30 days";
                    step = 120; // Downsample: ~1 hour interval
                    break;
                case "day":
                default:
                    timeFilter = "-1 day";
                    step = 1;
                    break;
            }
            
            StringBuilder json = new StringBuilder("[");
            // Use datetime filter and sort ASC for chart
            String sql = "SELECT timestamp, humidity, temp_supply, fan_rpm FROM humidity_readings " +
                         "WHERE timestamp >= datetime('now', '" + timeFilter + "') " +
                         "ORDER BY timestamp ASC";

            try (Connection conn = DriverManager.getConnection(DB_URL);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {

                boolean first = true;
                long counter = 0;
                while (rs.next()) {
                    // Simple downsampling
                    if (counter % step == 0) {
                        if (!first) json.append(",");
                        first = false;
                        
                        String ts = rs.getString("timestamp"); // SQLite returns string
                        int humidity = rs.getInt("humidity");
                        double temp = rs.getDouble("temp_supply");
                        int rpm = rs.getInt("fan_rpm");

                        json.append(String.format(
                            "{\"timestamp\":\"%s\", \"humidity\":%d, \"temp\":%.1f, \"rpm\":%d}",
                            ts, humidity, temp, rpm
                        ));
                    }
                    counter++;
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
    }

    private void pollAndStore() {
        try {
            if (!client.isConnected()) {
                log("Establishing connection to Genvex...");
                client.connect();
            }

            int humidity = client.readDatapoint(26);
            int tempSupplyRaw = client.readDatapoint(20);
            int supplyRpm = client.readDatapoint(35);
            int supplyDuty = client.readDatapoint(18);
            int extractRpm = client.readDatapoint(36);

            log("Polled Data: Humidity=" + humidity + "%, TempRaw=" + tempSupplyRaw + 
                ", SupplyRPM=" + supplyRpm + ", SupplyDuty=" + supplyDuty + ", ExtractRPM=" + extractRpm);

            if (humidity == -1 || tempSupplyRaw == -1 || supplyRpm == -1) {
                throw new IOException("Failed to read datapoints (returned -1)");
            }

            int tempSupplyOffsetRaw = Integer.parseInt(System.getenv().getOrDefault("TEMP_SUPPLY_OFFSET_RAW", "-300"));
            double tempSupply = (tempSupplyRaw + tempSupplyOffsetRaw) / 10.0;

            // Defrost Detection
            boolean isDefrosting = false;
            if (supplyRpm < 100 && extractRpm > 500 && tempSupply < 10.0) {
                isDefrosting = true;
                log("STATUS: Unit appears to be in DEFROST/ANTI-ICE mode (Supply Off, Extract On, Low Temp).");
            }

            // Apply Fan Speed Control
            updateFanSpeed(humidity, supplyRpm, supplyDuty, isDefrosting);

            // Determine active/estimated speed dynamically (ensures actual state is reflected at startup, 
            // under Monitor-Only mode, or when overridden physically outside the script)
            currentFanSpeed = estimateFanSpeed(supplyRpm, supplyDuty);

            lastHumidity = humidity;
            lastHumidityTime = System.currentTimeMillis();
            lastTemp = tempSupply;
            lastRpm = supplyRpm;

            if (saveToDatabase(humidity, tempSupply, supplyRpm)) {
                log("Logged: Humidity=" + humidity + "%, Temp=" + tempSupply + "C, RPM=" + supplyRpm + 
                    (boostActive ? " [BOOST ACTIVE]" : "") + (isDefrosting ? " [DEFROSTING]" : ""));
            } else {
                log("Read (Not Logged): Humidity=" + humidity + "%, Temp=" + tempSupply + "C, RPM=" + supplyRpm + 
                    (boostActive ? " [BOOST ACTIVE]" : "") + (isDefrosting ? " [DEFROSTING]" : ""));
            }
            
            // Update Home Assistant
            updateHomeAssistant(humidity, tempSupply, supplyRpm, currentFanSpeed);

        } catch (Exception e) {
            logError("Error polling data: " + e.getMessage());
            e.printStackTrace(); 
            // Try to reconnect next time
            client.disconnect();
        }
    }

    private void updateHomeAssistant(int humidity, double temp, int rpm, int speed) {
        String token = System.getenv("SUPERVISOR_TOKEN");
        if (token == null) return;

        sendToHA("sensor.genvex_humidity", String.valueOf(humidity), "%", "humidity", token);
        sendToHA("sensor.genvex_temp_supply", String.format("%.1f", temp), "°C", "temperature", token);
        sendToHA("sensor.genvex_fan_rpm", String.valueOf(rpm), "rpm", null, token);
        sendToHA("sensor.genvex_fan_speed", String.valueOf(speed), null, null, token);
    }

    private void sendToHA(String entityId, String state, String unit, String deviceClass, String token) {
        try {
            URL url = new URL("http://supervisor/core/api/states/" + entityId);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
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

    private void updateFanSpeed(int humidity, int currentRpm, int supplyDuty, boolean isDefrosting) {
        if (monitorOnly) {
            log("Monitor mode active. Recommended speed: " + NORMAL_SPEED + " (Reason: Monitor Only)");
            return;
        }

        int targetSpeed = NORMAL_SPEED;
        String reason = "Normal";

        // Manual override takes precedence over everything
        if (manualOverrideActive && System.currentTimeMillis() < manualOverrideEndTime) {
            targetSpeed = manualOverrideSpeed;
            reason = "Manual Override";
        } else if (staticRpmMode) {
            targetSpeed = staticRpmSpeed;
            reason = "Static RPM Mode";
                } else if (boostActive) {
            targetSpeed = BOOST_SPEED;
            reason = "Boost";
        } else {
            LocalTime now = LocalTime.now();
            boolean isNightTime = isNight(now);

            if (isNightTime) {
                targetSpeed = 1; // Night Mode (Lowest speed)
                reason = "Night Mode";
            } else {
                // General Humidity Control
                if (humidity >= HUMIDITY_VERY_HIGH_THRESHOLD) {
                    targetSpeed = 3;
                    reason = "Humidity Very High";
                } else if (humidity >= HUMIDITY_HIGH_THRESHOLD) {
                    targetSpeed = 2;
                    reason = "Humidity High";
                } else {
                    targetSpeed = 1;
                    reason = "Humidity Low";
                }
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

        if (targetSpeed != currentFanSpeed || forceUpdate) {
            if (isDefrosting) {
                 log("Defrost active. Not forcing fan speed update to avoid fighting controller.");
            } else {
                try {
                    log("Adjusting Fan Speed: " + currentFanSpeed + " -> " + targetSpeed + " (Reason: " + reason + ", Force: " + forceUpdate + ")");
                    client.setFanSpeed(targetSpeed);
                    currentFanSpeed = targetSpeed;
                } catch (Exception e) {
                    logError("Failed to set fan speed: " + e.getMessage());
                }
            }
        }
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
                     client.setFanSpeed(0);
                     Thread.sleep(5000);
                     log("Restart sequence: Setting fan to 1...");
                     client.setFanSpeed(1);
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
                     if (val != null) {
                        monitorOnly = Boolean.parseBoolean(val);
                        log("System Monitor Mode updated to: " + monitorOnly);
                        sendJson(t, "{\"status\": \"ok\", \"monitor_only\": " + monitorOnly + "}");
                     } else {
                        sendError(t, 400, "Missing monitor_only parameter");
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
        if (NIGHT_START.isBefore(NIGHT_END)) {
            return !time.isBefore(NIGHT_START) && !time.isAfter(NIGHT_END);
        } else {
            return !time.isBefore(NIGHT_START) || !time.isAfter(NIGHT_END);
        }
    }

    private void checkBoostLogic(int currentHumidity) {
        if (staticRpmMode) return; // Skip boost logic if static mode is active
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

            // Check for rapid rise or high humidity
            boolean rapidRise = (currentHumidity - lastHumidity) >= HUMIDITY_RISE_THRESHOLD;
            boolean highHumidity = currentHumidity >= HUMIDITY_HIGH_THRESHOLD;
            
            if (rapidRise || highHumidity) {
                LocalTime timeNow = LocalTime.now();
                boolean isNightTime = isNight(timeNow);

                if (isNightTime) {
                    if (rapidRise) {
                        log("Rapid humidity rise detected, but Boost is disabled at night.");
                    }
                } else {
                    if (rapidRise) {
                        log("Rapid humidity rise detected (" + lastHumidity + "% -> " + currentHumidity + "%). Activating Boost.");
                    } else {
                        log("High humidity detected (" + currentHumidity + "% >= " + HUMIDITY_HIGH_THRESHOLD + "%). Activating Boost.");
                    }
                    activateBoost(currentHumidity);
                }
            }
        } else {
            // Boost is active: use hysteresis to prevent rapid oscillation
            // Stay in boost until humidity is sufficiently low
            int boostExitHumidity = boostActivationHumidity - HUMIDITY_HYSTERESIS;
            
            // Ensure minimum boost duration before checking exit condition
            if (now >= boostMinEndTime) {
                if (currentHumidity <= boostExitHumidity) {
                    log("Humidity normalized (" + currentHumidity + "% <= " + boostExitHumidity + "%). Deactivating Boost.");
                    deactivateBoost();
                } else if (now >= boostEndTime) {
                    log("Boost duration exhausted (current humidity: " + currentHumidity + "%, target: " + boostExitHumidity + "%). Deactivating Boost anyway.");
                    deactivateBoost();
                }
            }
        }
    }

    private void activateBoost(int activationHumidity) {
        boostActive = true;
        boostActivationHumidity = activationHumidity;
        long now = System.currentTimeMillis();
        boostMinEndTime = now + (10 * 60 * 1000); // Minimum 10 minutes before checking exit condition
        boostEndTime = now + BOOST_DURATION_MS; // Absolute maximum
        log("Boost activated at " + activationHumidity + "% humidity. Min duration: 10 min, Max duration: " + (BOOST_DURATION_MS / 60000) + " min.");
    }

    private void deactivateBoost() {
        boostActive = false;
        boostActivationHumidity = -1;
        // Speed change will be handled by updateFanSpeed()
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

            manualOverrideActive = true;
            manualOverrideSpeed = level;
            manualOverrideEndTime = System.currentTimeMillis() + (durationMinutes * 60L * 1000L);

            try {
                client.setFanSpeed(level);
                currentFanSpeed = level;
            } catch (Exception e) {
                logError("Failed to set fan speed via Udluftning: " + e.getMessage());
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
                
                if (enabledStr != null) {
                    staticRpmMode = Boolean.parseBoolean(enabledStr);
                }
                if (speedStr != null) {
                    try {
                        staticRpmSpeed = Integer.parseInt(speedStr);
                    } catch (NumberFormatException e) {}
                }
                
                if (staticRpmMode) {
                    try {
                        client.setFanSpeed(staticRpmSpeed);
                        currentFanSpeed = staticRpmSpeed;
                        log("Static RPM Mode Activated: Speed " + staticRpmSpeed);
                    } catch (Exception e) {
                        logError("Failed to set fan speed for Static Mode: " + e.getMessage());
                    }
                } else {
                    log("Static RPM Mode Deactivated. Resuming auto control.");
                }
            }
            
            String json = String.format("{\"enabled\":%b,\"speed\":%d}", staticRpmMode, staticRpmSpeed);
            sendJson(t, json);
        }
    }

    private boolean saveToDatabase(int humidity, double tempSupply, int rpm) {
        String sql = "INSERT INTO humidity_readings (humidity, temp_supply, fan_rpm) VALUES (?, ?, ?)";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, humidity);
            pstmt.setDouble(2, tempSupply);
            pstmt.setInt(3, rpm);
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
