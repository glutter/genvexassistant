import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
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
            HumidityMonitor.ensureHistoryColumns(conn);

            String sql = HumidityMonitor.HistoryApiHandler.historyQuery("-1 day", 600);
            try (PreparedStatement query = conn.prepareStatement(sql)) {
                query.setString(1, "-1 day");
                try (ResultSet rs = query.executeQuery()) {
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
                    assertNull(rs.getString("control_reason"));
                    assertNull(rs.getObject("moisture"));
                    assertFalse(rs.getBoolean("control_event"));
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

    @Test
    void preservesBriefBoostAndReasonAndCommandChangesBeforeBucketing() throws Exception {
        for (int bucketSeconds : new int[] {600, 3600}) {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE humidity_readings (timestamp DATETIME, humidity INTEGER, "
                        + "temp_supply REAL, fan_rpm INTEGER)");
                HumidityMonitor.ensureHistoryColumns(conn);
                for (int sample = 0; sample < 7; sample++) {
                    int target = sample == 1 ? 3 : 1;
                    int commanded = sample == 3 ? 2 : 1;
                    String reason = sample == 1 ? "Shower boost" : sample >= 5 ? "Night mode" : "Normal";
                    stmt.execute("INSERT INTO humidity_readings "
                            + "(timestamp, humidity, target_speed, commanded_speed, control_reason) VALUES "
                            + "(datetime('now', 'start of day', '+" + sample + " seconds'), "
                            + (50 + sample) + ", " + target + ", " + commanded + ", '" + reason + "')");
                }
                try (PreparedStatement query = conn.prepareStatement(
                        HumidityMonitor.HistoryApiHandler.historyQuery("-7 days", bucketSeconds))) {
                    query.setString(1, "-7 days");
                    try (ResultSet rs = query.executeQuery()) {
                        for (int sample = 1; sample <= 6; sample++) {
                            assertTrue(rs.next());
                            assertEquals(50 + sample, rs.getInt("humidity"));
                            assertEquals(sample < 6, rs.getBoolean("control_event"));
                        }
                        assertFalse(rs.next());
                    }
                }
            }
        }
    }

    @Test
    void shortRangesFilterRawSamplesAndUnknownRangesUseDay() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE humidity_readings (timestamp DATETIME, humidity INTEGER, "
                    + "temp_supply REAL, fan_rpm INTEGER)");
            HumidityMonitor.ensureHistoryColumns(conn);
            for (int hours : new int[] {1, 4, 8, 20, 25}) {
                stmt.execute("INSERT INTO humidity_readings (timestamp, humidity) VALUES "
                        + "(datetime('now', '-" + hours + " hours'), 50)");
            }
            String[] ranges = {"3h", "6h", "12h", "day", "invalid' OR 1=1--"};
            for (int index = 0; index < ranges.length; index++) {
                String filter = HumidityMonitor.HistoryApiHandler.historyTimeFilter(ranges[index]);
                assertEquals(0, HumidityMonitor.HistoryApiHandler.historyBucketSeconds(ranges[index]));
                try (PreparedStatement query = conn.prepareStatement(
                        HumidityMonitor.HistoryApiHandler.historyQuery(filter, 0))) {
                    query.setString(1, filter);
                    try (ResultSet rs = query.executeQuery()) {
                        int count = 0;
                        while (rs.next()) count++;
                        assertEquals(Math.min(index + 1, 4), count);
                    }
                }
            }
            assertThrows(IllegalArgumentException.class,
                    () -> HumidityMonitor.HistoryApiHandler.historyQuery("' OR 1=1--", 0));
        }
    }

    @Test
    void firstVisibleEventComparesWithRawRowBeforeRange() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE humidity_readings (timestamp DATETIME, humidity INTEGER, "
                    + "temp_supply REAL, fan_rpm INTEGER)");
            HumidityMonitor.ensureHistoryColumns(conn);
            stmt.execute("INSERT INTO humidity_readings (timestamp, target_speed) VALUES "
                    + "(datetime('now', '-4 hours'), 1), (datetime('now', '-2 hours'), 3)");
            try (PreparedStatement query = conn.prepareStatement(
                    HumidityMonitor.HistoryApiHandler.historyQuery("-3 hours", 0))) {
                query.setString(1, "-3 hours");
                try (ResultSet rs = query.executeQuery()) {
                    assertTrue(rs.next());
                    assertTrue(rs.getBoolean("control_event"));
                    assertNull(rs.getString("control_reason"));
                    assertFalse(rs.next());
                }
            }
        }
    }

    @Test
    void persistsThePollSnapshotAndNullMeasurementsWithoutReadingMutableState() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE humidity_readings (timestamp DATETIME, humidity INTEGER, "
                    + "temp_supply REAL, fan_rpm INTEGER)");
            HumidityMonitor.ensureHistoryColumns(conn);
            HumidityMonitor monitor = new HumidityMonitor("unused", "unused");
            String reason = "Gentle humidity recovery + Heat-loss guard";
            HumidityMonitor.ControlTelemetry telemetry = monitor.recordSuccessfulTelemetry(
                    new HumidityMonitor.ControlDecision(reason, 2, 1), 60, 20, 50, 7,
                    Instant.parse("2026-09-21T12:00:00Z"));
            HumidityMonitor.PollResult reading = new HumidityMonitor.PollResult(60, 18, 10, 19, 20,
                    1000, 2, 0, true, HumidityMonitor.DefrostState.INACTIVE, 2, 5000, telemetry);
            monitor.recordSuccessfulTelemetry(new HumidityMonitor.ControlDecision("Normal", 1, 1),
                    40, 20, Double.NaN, Double.NaN, Instant.parse("2026-09-21T12:00:30Z"));
            HumidityMonitor.insertReading(conn, reading);
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM humidity_readings")) {
                assertTrue(rs.next());
                assertEquals(reason, rs.getString("control_reason"));
                assertEquals(2, rs.getInt("policy_speed"));
                assertEquals(1, rs.getInt("target_speed"));
                assertEquals(2, rs.getInt("commanded_speed"));
                assertEquals(50, rs.getDouble("recovery_baseline"));
                assertEquals(HumidityPhysics.mixingRatioGramsPerKg(60, 20), rs.getDouble("moisture"));
                assertEquals("2026-09-21 12:00:00", rs.getString("timestamp"));
            }
            HumidityMonitor.ControlTelemetry missing = monitor.recordSuccessfulTelemetry(
                    new HumidityMonitor.ControlDecision("Monitor only", -1, -1), 60, Double.NaN,
                    Double.NaN, Double.NaN, Instant.parse("2026-09-21T12:01:00Z"));
            HumidityMonitor.insertReading(conn, new HumidityMonitor.PollResult(60, 18, 10, 19, Double.NaN,
                    1000, 1, -1, false, HumidityMonitor.DefrostState.INACTIVE, 1, 3000, missing));
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM humidity_readings ORDER BY timestamp DESC LIMIT 1")) {
                assertTrue(rs.next());
                assertNull(rs.getObject("policy_speed"));
                assertNull(rs.getObject("target_speed"));
                assertNull(rs.getObject("recovery_baseline"));
                assertNull(rs.getObject("moisture"));
            }
        }
    }
}