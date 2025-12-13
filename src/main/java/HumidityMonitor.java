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
            server.setExecutor(null);
            server.start();
            log("Web Dashboard started on port " + WEB_PORT);
        } catch (IOException e) {
            logError("Failed to start web server: " + e.getMessage());
        }
    }

    class LiveApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            t.getResponseHeaders().add("Content-Type", "application/json");
            
            String json = String.format(
                "{\"humidity\":%d, \"temp\":%.1f, \"rpm\":%d, \"fan_speed\":%d, \"boost\":%b}",
                lastHumidity, lastTemp, lastRpm, currentFanSpeed, boostActive
            );

            t.sendResponseHeaders(200, json.length());
            try (OutputStream os = t.getResponseBody()) {
                os.write(json.getBytes());
            }
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
            
            StringBuilder json = new StringBuilder("[");
            String sql = "SELECT timestamp, humidity, temp_supply, fan_rpm FROM humidity_readings ORDER BY timestamp DESC LIMIT 2880"; // Last 24h (30s intervals)

            try (Connection conn = DriverManager.getConnection(DB_URL);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {

                boolean first = true;
                while (rs.next()) {
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

            } catch (Exception e) {
                // Log the error but return empty list so the dashboard doesn't break
                System.err.println("[HistoryApiHandler] Database error: " + e.getMessage());
                // If we want to return an empty list, we just continue.
                // The json StringBuilder already has "["
            }

            json.append("]");
            String response = json.toString();
            
            t.sendResponseHeaders(200, response.length());
            try (OutputStream os = t.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }

    private void pollAndStore() {
        try {
            // Always reconnect before polling to avoid stale UDP sessions
            // The Genvex unit tends to drop connections after ~60 seconds of inactivity
            if (client.isConnected()) {
                client.disconnect();
            }
            client.connect();

            int humidity = client.readDatapoint(26);
            int tempSupplyRaw = client.readDatapoint(20);
            int rpm = client.readDatapoint(35);

            if (humidity == -1 || tempSupplyRaw == -1 || rpm == -1) {
                throw new IOException("Failed to read datapoints (returned -1)");
            }

            // We don't have a direct "Fan Speed Level" read, so we might infer it or leave null
            // For now, we'll just store what we know.
            
            // Optima 270 temperature offset: raw values include +300 (i.e., +30.0C)
            // Reference: `reference/temp_genvex_nabto/src/genvexnabto/models/optima270.py` uses offset -300 with divider 10
            // Apply configurable offset via env var, defaulting to -300 for Optima 270
            int tempSupplyOffsetRaw = Integer.parseInt(System.getenv().getOrDefault("TEMP_SUPPLY_OFFSET_RAW", "-300"));
            double tempSupply = (tempSupplyRaw + tempSupplyOffsetRaw) / 10.0;

            // Check for boost conditions
            if (BOOST_ENABLED) {
                checkBoostLogic(humidity);
            }
            
            // Apply Fan Speed Control (Boost, Night Mode, or General Humidity)
            updateFanSpeed(humidity);

            lastHumidity = humidity;
            lastHumidityTime = System.currentTimeMillis();
            lastTemp = tempSupply;
            lastRpm = rpm;

            if (saveToDatabase(humidity, tempSupply, rpm)) {
                log("Logged: Humidity=" + humidity + "%, Temp=" + tempSupply + "C, RPM=" + rpm + (boostActive ? " [BOOST ACTIVE]" : ""));
            } else {
                log("Read (Not Logged): Humidity=" + humidity + "%, Temp=" + tempSupply + "C, RPM=" + rpm + (boostActive ? " [BOOST ACTIVE]" : ""));
            }
            
            // Update Home Assistant
            updateHomeAssistant(humidity, tempSupply, rpm, currentFanSpeed);

        } catch (Exception e) {
            logError("Error polling data: " + e.getMessage());
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

    private void updateFanSpeed(int humidity) {
        int targetSpeed = NORMAL_SPEED;

        // Manual override takes precedence over everything
        if (manualOverrideActive && System.currentTimeMillis() < manualOverrideEndTime) {
            targetSpeed = manualOverrideSpeed;
        } else if (boostActive) {
            targetSpeed = BOOST_SPEED;
        } else {
            LocalTime now = LocalTime.now();
            boolean isNight = now.isAfter(NIGHT_START) || now.isBefore(NIGHT_END);

            if (isNight) {
                targetSpeed = 1; // Night Mode (Lowest speed)
            } else {
                // General Humidity Control
                // >= 80 -> Speed 3 (Boost)
                // >= 65 -> Speed 2 (Normal)
                // < 65  -> Speed 1 (Low)
                
                if (humidity >= HUMIDITY_VERY_HIGH_THRESHOLD) {
                    targetSpeed = 3;
                } else if (humidity >= HUMIDITY_HIGH_THRESHOLD) {
                    targetSpeed = 2;
                } else {
                    targetSpeed = 1;
                }
            }
        }

        if (targetSpeed != currentFanSpeed) {
            try {
                log("Adjusting Fan Speed: " + currentFanSpeed + " -> " + targetSpeed);
                client.setFanSpeed(targetSpeed);
                currentFanSpeed = targetSpeed;
            } catch (Exception e) {
                logError("Failed to set fan speed: " + e.getMessage());
            }
        }
    }

    private void checkBoostLogic(int currentHumidity) {
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
                boolean isNight = timeNow.isAfter(NIGHT_START) || timeNow.isBefore(NIGHT_END);

                if (isNight) {
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
                String response = "Method Not Allowed";
                t.sendResponseHeaders(405, response.length());
                try (OutputStream os = t.getResponseBody()) { os.write(response.getBytes()); }
                return;
            }

            java.io.InputStream is = t.getRequestBody();
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = is.read(buf)) != -1) { baos.write(buf, 0, n); }
            byte[] body = baos.toByteArray();
            String payload = new String(body);
            int level = NORMAL_SPEED;
            int durationMinutes = 30;
            try {
                // Very simple JSON parsing without external libs
                // Expecting: {"level":2,"duration_minutes":30}
                String p = payload.replaceAll("\\s", "");
                if (p.contains("\"level\"")) {
                    String part = p.split("\"level\":")[1];
                    String num = part.split("[,}]")[0];
                    level = Integer.parseInt(num);
                }
                if (p.contains("\"duration_minutes\"")) {
                    String part = p.split("\"duration_minutes\":")[1];
                    String num = part.split("[,}]")[0];
                    durationMinutes = Integer.parseInt(num);
                }
            } catch (Exception e) {
                // Use defaults if parsing fails
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
            t.getResponseHeaders().add("Content-Type", "application/json");
            t.sendResponseHeaders(200, json.length());
            try (OutputStream os = t.getResponseBody()) { os.write(json.getBytes()); }
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
        String sql = "DELETE FROM humidity_readings WHERE timestamp < datetime('now', '-14 days')";
        
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            int deleted = pstmt.executeUpdate();
            log("Cleanup: Removed " + deleted + " old records.");

        } catch (SQLException e) {
            logError("Cleanup error: " + e.getMessage());
        }
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
