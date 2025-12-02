import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

public class GenvexServer {
    private static final int PORT = 8080;
    private static final String GENVEX_IP = "192.168.1.100";
    private static final String EMAIL = "user@example.com";
    
    private static GenvexClient client;

    public static void main(String[] args) throws IOException {
        client = new GenvexClient(GENVEX_IP, EMAIL);
        
        try {
            System.out.println("Connecting to Genvex unit...");
            client.connect();
            System.out.println("Connected!");
        } catch (Exception e) {
            System.err.println("Failed to connect: " + e.getMessage());
            // Continue anyway, maybe we can reconnect later or on request
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/status", new StatusHandler());
        server.createContext("/speed", new SpeedHandler());
        server.setExecutor(null); // creates a default executor
        server.start();
        System.out.println("Server started on port " + PORT);
    }

    static class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!client.isConnected()) {
                try {
                    client.connect();
                } catch (Exception e) {
                    String response = "{\"error\": \"Not connected to Genvex unit\"}";
                    sendResponse(t, 500, response);
                    return;
                }
            }

            try {
                int tempSupply = client.readDatapoint(20);
                int tempOutside = client.readDatapoint(21);
                int humidity = client.readDatapoint(26);
                int dutySupply = client.readDatapoint(18);
                int rpmSupply = client.readDatapoint(35);

                String json = String.format(
                    "{" +
                    "\"temp_supply\": %.1f," +
                    "\"temp_outside\": %.1f," +
                    "\"humidity\": %d," +
                    "\"fan_duty\": %d," +
                    "\"fan_rpm\": %d" +
                    "}",
                    tempSupply / 10.0,
                    tempOutside / 10.0,
                    humidity,
                    dutySupply / 100,
                    rpmSupply
                );

                sendResponse(t, 200, json);
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(t, 500, "{\"error\": \"Failed to read data\"}");
            }
        }
    }

    static class SpeedHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equals(t.getRequestMethod())) {
                String query = t.getRequestURI().getQuery();
                Map<String, String> params = queryToMap(query);
                
                if (params.containsKey("level")) {
                    try {
                        int level = Integer.parseInt(params.get("level"));
                        if (!client.isConnected()) client.connect();
                        
                        client.setFanSpeed(level);
                        sendResponse(t, 200, "{\"success\": true, \"level\": " + level + "}");
                    } catch (NumberFormatException e) {
                        sendResponse(t, 400, "{\"error\": \"Invalid level format\"}");
                    } catch (Exception e) {
                        e.printStackTrace();
                        sendResponse(t, 500, "{\"error\": \"Failed to set speed\"}");
                    }
                } else {
                    sendResponse(t, 400, "{\"error\": \"Missing 'level' parameter\"}");
                }
            } else {
                sendResponse(t, 405, "{\"error\": \"Method not allowed\"}");
            }
        }
    }

    private static void sendResponse(HttpExchange t, int statusCode, String response) throws IOException {
        t.getResponseHeaders().set("Content-Type", "application/json");
        t.sendResponseHeaders(statusCode, response.length());
        OutputStream os = t.getResponseBody();
        os.write(response.getBytes());
        os.close();
    }

    private static Map<String, String> queryToMap(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null) return result;
        for (String param : query.split("&")) {
            String[] entry = param.split("=");
            if (entry.length > 1) {
                result.put(entry[0], entry[1]);
            } else {
                result.put(entry[0], "");
            }
        }
        return result;
    }
}
