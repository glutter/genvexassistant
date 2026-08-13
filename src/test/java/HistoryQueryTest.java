import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class HistoryQueryTest {
    @Test
    void emitsUtcTimestampsAndKeepsNewestRowInEachBucket() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE humidity_readings (timestamp DATETIME, humidity INTEGER, "
                    + "temp_supply REAL, temp_outside REAL, temp_exhaust REAL, temp_extract REAL, "
                    + "fan_rpm INTEGER, fan_speed_level INTEGER)");
            stmt.execute("INSERT INTO humidity_readings VALUES "
                    + "(strftime('%Y-%m-%d %H:%M:00', 'now'), 40, 18, 17, 19, 22, 1000, 1), "
                    + "(datetime(strftime('%Y-%m-%d %H:%M:00', 'now'), '+1 second'), "
                    + "41, 18, 17, 19, 22, 1001, 2)");

            String sql = HumidityMonitor.HistoryApiHandler.historyQuery("-1 day", 600);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                int count = 0;
                int newestRpm = -1;
                String timestamp = null;
                while (rs.next()) {
                    count++;
                    newestRpm = rs.getInt("fan_rpm");
                    timestamp = rs.getString("timestamp_utc");
                }
                assertEquals(1, count);
                assertEquals(1001, newestRpm);
                assertTrue(timestamp.endsWith("Z"));
            }
        }
    }
}