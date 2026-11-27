import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The fan writes that automatic control made, with the reason, so another service (husstemme) can show what the unit
 * did on its own. Writes that come in through the API are not here: the caller already knows about them.
 */
final class FanCommandLog {
    static final int MAX_PER_PAGE = 500;
    // Same span as the readings: a full heating season plus overlap.
    static final long RETENTION_MILLIS = 400L * 24 * 60 * 60 * 1000;

    record FanCommand(long atMillis, int fromSpeed, int toSpeed, String reason, boolean ok) {}

    private FanCommandLog() {}

    static void ensureTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS fan_commands ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "at_ms INTEGER NOT NULL, "
                    + "from_speed INTEGER, "
                    + "to_speed INTEGER NOT NULL, "
                    + "reason TEXT NOT NULL, "
                    + "ok INTEGER NOT NULL)");
        }
    }

    static void insert(Connection connection, FanCommand command) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO fan_commands (at_ms, from_speed, to_speed, reason, ok) VALUES (?, ?, ?, ?, ?)")) {
            statement.setLong(1, command.atMillis());
            if (command.fromSpeed() >= 0) {
                statement.setInt(2, command.fromSpeed());
            } else {
                statement.setNull(2, java.sql.Types.INTEGER);
            }
            statement.setInt(3, command.toSpeed());
            statement.setString(4, command.reason());
            statement.setInt(5, command.ok() ? 1 : 0);
            statement.executeUpdate();
        }
    }

    /**
     * Commands with an id above {@code afterId}, oldest first, at most {@link #MAX_PER_PAGE}. {@code latest} is the
     * highest id in the table. A reader whose cursor is above it knows the database was replaced.
     */
    static String json(Connection connection, long afterId) throws SQLException {
        long latest = 0;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM fan_commands")) {
            if (result.next()) latest = result.getLong(1);
        }
        StringBuilder json = new StringBuilder("{\"latest\":").append(latest).append(",\"commands\":[");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, at_ms, from_speed, to_speed, reason, ok FROM fan_commands WHERE id > ? ORDER BY id LIMIT ?")) {
            statement.setLong(1, afterId);
            statement.setInt(2, MAX_PER_PAGE);
            try (ResultSet result = statement.executeQuery()) {
                boolean first = true;
                while (result.next()) {
                    if (!first) json.append(',');
                    first = false;
                    int from = result.getInt("from_speed");
                    String fromJson = result.wasNull() ? "null" : String.valueOf(from);
                    json.append("{\"id\":").append(result.getLong("id"))
                            .append(",\"at\":").append(result.getLong("at_ms"))
                            .append(",\"from\":").append(fromJson)
                            .append(",\"to\":").append(result.getInt("to_speed"))
                            .append(",\"reason\":").append(HumidityMonitor.jsonString(result.getString("reason")))
                            .append(",\"ok\":").append(result.getInt("ok") == 1)
                            .append('}');
                }
            }
        }
        return json.append("]}").toString();
    }

    static int deleteExpired(Connection connection, long nowMillis) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM fan_commands WHERE at_ms < ?")) {
            statement.setLong(1, nowMillis - RETENTION_MILLIS);
            return statement.executeUpdate();
        }
    }
}
