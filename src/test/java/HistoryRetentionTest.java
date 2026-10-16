import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

class HistoryRetentionTest {
    @Test
    void keepsThirteenMonthsOfReadings() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE humidity_readings (timestamp DATETIME, humidity INTEGER)");
            stmt.execute("INSERT INTO humidity_readings VALUES "
                    + "(datetime('now', '-1 day'), 1), "
                    + "(datetime('now', '-12 months', '-27 days'), 2), "
                    + "(datetime('now', '-13 months', '-1 day'), 3)");

            assertEquals(1, HumidityMonitor.deleteExpiredReadings(conn));

            try (ResultSet rs = stmt.executeQuery("SELECT humidity FROM humidity_readings ORDER BY humidity")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
                assertFalse(rs.next());
            }
        }
    }
}
