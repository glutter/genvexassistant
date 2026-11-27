import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;

import org.junit.jupiter.api.Test;

class FanCommandLogTest {
    @Test
    void readerGetsOnlyNewCommandsAndOldOnesExpire() throws Exception {
        long now = 1_800_000_000_000L;
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            FanCommandLog.ensureTable(conn);
            FanCommandLog.ensureTable(conn);
            FanCommandLog.insert(conn, new FanCommandLog.FanCommand(now - FanCommandLog.RETENTION_MILLIS - 1, 2, 3,
                    "Shower boost", true));
            FanCommandLog.insert(conn, new FanCommandLog.FanCommand(now, -1, 1, "Normal + \"Heat-loss guard\"", false));

            assertEquals("{\"latest\":2,\"commands\":[{\"id\":2,\"at\":" + now
                    + ",\"from\":null,\"to\":1,\"reason\":\"Normal + \\\"Heat-loss guard\\\"\",\"ok\":false}]}",
                    FanCommandLog.json(conn, 1));

            assertEquals(1, FanCommandLog.deleteExpired(conn, now));
            assertEquals("{\"latest\":2,\"commands\":[]}", FanCommandLog.json(conn, 2));
        }
    }
}
