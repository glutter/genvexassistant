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

public class HumidityMonitor {

    private static final String DB_URL = "jdbc:postgresql://localhost:5432/genvex";
    private static final String DB_USER = System.getenv().getOrDefault("DB_USER", "postgres");
    private static final String DB_PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "123456");
    private static final int WEB_PORT = 8081; // Different from GenvexServer 8080

    private final GenvexClient client;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    // Boost Configuration
    private static final boolean BOOST_ENABLED = Boolean.parseBoolean(System.getenv().getOrDefault("BOOST_ENABLED", "true"));
    private static final int HUMIDITY_RISE_THRESHOLD = 2; // % rise per poll (30s)
    private static final int BOOST_SPEED = 3;
    private static final int NORMAL_SPEED = 2;
    private static final long BOOST_DURATION_MS = 15 * 60 * 1000; // 15 minutes

    // General Control Configuration
    private static final int HUMIDITY_HIGH_THRESHOLD = 60;
    private static final int HUMIDITY_LOW_THRESHOLD = 30;
    private static final LocalTime NIGHT_START = LocalTime.of(23, 0);
    private static final LocalTime NIGHT_END = LocalTime.of(6, 30);

    // State
    private int lastHumidity = -1;
    private boolean boostActive = false;
    private long boostEndTime = 0;
    private int currentFanSpeed = -1;

    public HumidityMonitor(String ip, String email) {
        this.client = new GenvexClient(ip, email);
    }

    public void start() {
        // Start Web Server
        startWebServer();

        // Run every 30 seconds
        scheduler.scheduleAtFixedRate(this::pollAndStore, 0, 30, TimeUnit.SECONDS);
        
        // Run cleanup daily
        scheduler.scheduleAtFixedRate(this::cleanupOldData, 1, 24, TimeUnit.HOURS);
        
        System.out.println("Humidity Monitor started.");
    }

    private void startWebServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(WEB_PORT), 0);
            server.createContext("/", new StaticFileHandler());
            server.createContext("/api/history", new HistoryApiHandler());
            server.setExecutor(null);
            server.start();
            log("Web Dashboard started on port " + WEB_PORT);
        } catch (IOException e) {
            logError("Failed to start web server: " + e.getMessage());
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

            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {

                boolean first = true;
                while (rs.next()) {
                    if (!first) json.append(",");
                    first = false;
                    
                    Timestamp ts = rs.getTimestamp("timestamp");
                    int humidity = rs.getInt("humidity");
                    double temp = rs.getDouble("temp_supply");
                    int rpm = rs.getInt("fan_rpm");

                    json.append(String.format(
                        "{\"timestamp\":\"%s\", \"humidity\":%d, \"temp\":%.1f, \"rpm\":%d}",
                        ts.toString(), humidity, temp, rpm
                    ));
                }

            } catch (SQLException e) {
                e.printStackTrace();
                String error = "{\"error\":\"" + e.getMessage() + "\"}";
                t.sendResponseHeaders(500, error.length());
                try (OutputStream os = t.getResponseBody()) {
                    os.write(error.getBytes());
                }
                return;
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
            if (!client.isConnected()) {
                client.connect();
            }

            int humidity = client.readDatapoint(26);
            int tempSupplyRaw = client.readDatapoint(20);
            int rpm = client.readDatapoint(35);

            if (humidity == -1 || tempSupplyRaw == -1 || rpm == -1) {
                throw new IOException("Failed to read datapoints (returned -1)");
            }

            // We don't have a direct "Fan Speed Level" read, so we might infer it or leave null
            // For now, we'll just store what we know.
            
            double tempSupply = tempSupplyRaw / 10.0;

            // Check for boost conditions
            if (BOOST_ENABLED) {
                checkBoostLogic(humidity);
            }
            
            // Apply Fan Speed Control (Boost, Night Mode, or General Humidity)
            updateFanSpeed(humidity);

            lastHumidity = humidity;

            if (saveToDatabase(humidity, tempSupply, rpm)) {
                log("Logged: Humidity=" + humidity + "%, Temp=" + tempSupply + "C, RPM=" + rpm + (boostActive ? " [BOOST ACTIVE]" : ""));
            } else {
                log("Read (Not Logged): Humidity=" + humidity + "%, Temp=" + tempSupply + "C, RPM=" + rpm + (boostActive ? " [BOOST ACTIVE]" : ""));
            }

        } catch (Exception e) {
            logError("Error polling data: " + e.getMessage());
            // Try to reconnect next time
            client.disconnect();
        }
    }

    private void updateFanSpeed(int humidity) {
        int targetSpeed = NORMAL_SPEED;

        if (boostActive) {
            targetSpeed = BOOST_SPEED;
        } else {
            LocalTime now = LocalTime.now();
            boolean isNight = now.isAfter(NIGHT_START) || now.isBefore(NIGHT_END);

            if (isNight) {
                targetSpeed = 1; // Night Mode
            } else {
                // General Humidity Control
                if (humidity >= HUMIDITY_HIGH_THRESHOLD) {
                    targetSpeed = 3;
                } else if (humidity <= HUMIDITY_LOW_THRESHOLD) {
                    targetSpeed = 1;
                } else {
                    targetSpeed = NORMAL_SPEED;
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

        if (!boostActive) {
            // Check for rapid rise
            if ((currentHumidity - lastHumidity) >= HUMIDITY_RISE_THRESHOLD) {
                log("Rapid humidity rise detected (" + lastHumidity + "% -> " + currentHumidity + "%). Activating Boost.");
                activateBoost();
            }
        } else {
            // Check if we should deactivate
            if (System.currentTimeMillis() >= boostEndTime) {
                if (currentHumidity <= lastHumidity) {
                     log("Boost time over and humidity stable. Deactivating Boost.");
                     deactivateBoost();
                } else {
                    log("Boost time over but humidity still rising. Extending 5 minutes...");
                    boostEndTime = System.currentTimeMillis() + (5 * 60 * 1000);
                }
            }
        }
    }

    private void activateBoost() {
        boostActive = true;
        boostEndTime = System.currentTimeMillis() + BOOST_DURATION_MS;
        // Speed change will be handled by updateFanSpeed()
    }

    private void deactivateBoost() {
        boostActive = false;
        // Speed change will be handled by updateFanSpeed()
    }

    private boolean saveToDatabase(int humidity, double tempSupply, int rpm) {
        String sql = "INSERT INTO humidity_readings (humidity, temp_supply, fan_rpm) VALUES (?, ?, ?)";

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, humidity);
            pstmt.setDouble(2, tempSupply);
            pstmt.setInt(3, rpm);
            pstmt.executeUpdate();
            return true;

        } catch (SQLException e) {
            logError("Database error: " + e.getMessage());
            return false;
        }
    }

    private void cleanupOldData() {
        String sql = "DELETE FROM humidity_readings WHERE timestamp < NOW() - INTERVAL '14 days'";
        
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            int deleted = pstmt.executeUpdate();
            log("Cleanup: Removed " + deleted + " old records.");

        } catch (SQLException e) {
            logError("Cleanup error: " + e.getMessage());
        }
    }

    private void log(String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        System.out.println("[" + timestamp + "] " + message);
    }

    private void logError(String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        System.err.println("[" + timestamp + "] " + message);
    }

    public static void main(String[] args) {
        // Example usage
        HumidityMonitor monitor = new HumidityMonitor("192.168.1.100", "user@example.com");
        monitor.start();
    }
}
