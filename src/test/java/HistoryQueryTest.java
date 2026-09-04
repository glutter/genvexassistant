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
                    + "fan_rpm INTEGER, fan_speed_level INTEGER, bypass_open INTEGER, "
                    + "commanded_speed INTEGER, supply_duty INTEGER)");
            stmt.execute("INSERT INTO humidity_readings VALUES "
                    + "(strftime('%Y-%m-%d %H:%M:00', 'now'), 40, 18, 17, 19, 22, 1000, 1, 0, 2, 3000), "
                    + "(datetime(strftime('%Y-%m-%d %H:%M:00', 'now'), '+1 second'), "
                    + "41, 18, 17, 19, 22, 1001, 2, 1, 2, 5000)");

            String sql = HumidityMonitor.HistoryApiHandler.historyQuery("-1 day", 600);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                int count = 0;
                int newestRpm = -1;
                int newestBypassState = -1;
                int newestCommandedSpeed = -1;
                int newestSupplyDuty = -1;
                String timestamp = null;
                while (rs.next()) {
                    count++;
                    newestRpm = rs.getInt("fan_rpm");
                    newestBypassState = rs.getInt("bypass_open");
                    newestCommandedSpeed = rs.getInt("commanded_speed");
                    newestSupplyDuty = rs.getInt("supply_duty");
                    timestamp = rs.getString("timestamp_utc");
                }
                assertEquals(1, count);
                assertEquals(1001, newestRpm);
                assertEquals(1, newestBypassState);
                assertEquals(2, newestCommandedSpeed);
                assertEquals(5000, newestSupplyDuty);
                assertTrue(timestamp.endsWith("Z"));
            }
        }
    }
}