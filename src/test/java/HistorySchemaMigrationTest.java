import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class HistorySchemaMigrationTest {
    @Test
    void addsExtendedTelemetryColumnsToLegacyDatabase() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE humidity_readings (timestamp DATETIME, humidity INTEGER, " +
                    "temp_supply REAL, fan_rpm INTEGER)");

            HumidityMonitor.ensureHistoryColumns(conn);
            HumidityMonitor.ensureHistoryColumns(conn);
            HumidityMonitor.ensureControlStateTable(conn);
            HumidityMonitor.ensureControlStateTable(conn);

            Set<String> columns = new HashSet<>();
            try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(humidity_readings)")) {
                while (rs.next()) {
                    columns.add(rs.getString("name"));
                }
            }

            assertTrue(columns.contains("temp_outside"));
            assertTrue(columns.contains("temp_exhaust"));
            assertTrue(columns.contains("temp_extract"));
            assertTrue(columns.contains("fan_speed_level"));
            assertTrue(columns.contains("bypass_open"));

            boolean timestampIndexFound = false;
            try (ResultSet rs = stmt.executeQuery("PRAGMA index_list(humidity_readings)")) {
                while (rs.next()) {
                    timestampIndexFound |= "idx_humidity_timestamp".equals(rs.getString("name"));
                }
            }
            assertTrue(timestampIndexFound);

            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS row_count FROM control_state")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt("row_count") == 1);
            }
        }
    }
}