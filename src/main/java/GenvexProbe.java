import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only register dump: sends DATAPOINT_READ (0x2d) and SETPOINT_READ (0x2a) only, never a write.
 *
 * <pre>
 * GENVEX_IP=... GENVEX_EMAIL=... java -cp ha_addon/app.jar GenvexProbe [--max 127] [--retries 2]
 *         [--watch MINUTES] [--every SECONDS]
 * </pre>
 *
 * Prints CSV (with '#' comment lines): every datapoint and setpoint 0..max with the raw reply bytes, a
 * summary of the registers behind the address-map questions, and with --watch a time series of supply
 * duty, setpoint 7 and datapoint 24, so a fan-speed change can be matched against each of them.
 */
public class GenvexProbe {
    private static final int NO_VALUE = Integer.MIN_VALUE;

    private record Ref(String name, int divider, int offset) {}

    // Addresses and scaling as listed in genvexnabto models/optima270.py; used as labels only.
    private static final Map<Integer, Ref> DATAPOINTS = Map.ofEntries(
            Map.entry(18, new Ref("DUTYCYCLE_SUPPLY", 100, 0)),
            Map.entry(19, new Ref("DUTYCYCLE_EXTRACT", 100, 0)),
            Map.entry(20, new Ref("TEMP_SUPPLY", 10, -300)),
            Map.entry(21, new Ref("TEMP_OUTSIDE", 10, -300)),
            Map.entry(22, new Ref("TEMP_EXHAUST", 10, -300)),
            Map.entry(23, new Ref("TEMP_EXTRACT", 10, -300)),
            Map.entry(24, new Ref("TEMP_FROSTPROTECTION", 10, -300)),
            Map.entry(26, new Ref("HUMIDITY", 1, 0)),
            Map.entry(35, new Ref("RPM_SUPPLY", 1, 0)),
            Map.entry(36, new Ref("RPM_EXTRACT", 1, 0)),
            Map.entry(40, new Ref("BYPASS_ANALOG", 1, 0)),
            Map.entry(41, new Ref("PREHEAT_PWM", 100, 0)),
            Map.entry(42, new Ref("REHEAT_PWM", 100, 0)),
            Map.entry(50, new Ref("ROTOR_SPEED", 1, 0)),
            Map.entry(53, new Ref("BYPASS_ACTIVE", 1, 0)),
            Map.entry(114, new Ref("ALARM_OPTIMA270_1", 1, 0)),
            Map.entry(115, new Ref("ALARM_OPTIMA270_2", 1, 0)));

    // Setpoint READ addresses; genvexnabto writes them at other addresses (FAN_SPEED: read 7, write 24).
    private static final Map<Integer, Ref> SETPOINTS = Map.ofEntries(
            Map.entry(1, new Ref("TEMP_SETPOINT", 10, 100)),
            Map.entry(3, new Ref("REHEATING", 1, 0)),
            Map.entry(6, new Ref("HUMIDITY_CONTROL", 1, 0)),
            Map.entry(7, new Ref("FAN_SPEED", 1, 0)),
            Map.entry(8, new Ref("SUPPLY_AIR_LEVEL4", 1, 0)),
            Map.entry(9, new Ref("EXTRACT_AIR_LEVEL4", 1, 0)),
            Map.entry(10, new Ref("SUPPLY_AIR_LEVEL1", 1, 0)),
            Map.entry(11, new Ref("SUPPLY_AIR_LEVEL2", 1, 0)),
            Map.entry(12, new Ref("SUPPLY_AIR_LEVEL3", 1, 0)),
            Map.entry(13, new Ref("EXTRACT_AIR_LEVEL1", 1, 0)),
            Map.entry(14, new Ref("EXTRACT_AIR_LEVEL2", 1, 0)),
            Map.entry(15, new Ref("EXTRACT_AIR_LEVEL3", 1, 0)),
            Map.entry(21, new Ref("BYPASS_OPENOFFSET", 10, 0)),
            Map.entry(29, new Ref("BYPASS_TURNOFF", 1, 0)),
            Map.entry(30, new Ref("BOOST_ENABLE", 1, 0)),
            Map.entry(50, new Ref("FILTER_RESET", 1, 0)),
            Map.entry(57, new Ref("BYPASS_FORCE_SPEED", 1, 0)),
            Map.entry(58, new Ref("BYPASS_FORCE_TEMP", 10, 0)),
            Map.entry(59, new Ref("BYPASS_MIN_ON", 1, 0)),
            Map.entry(70, new Ref("BOOST_TIME", 1, 0)),
            Map.entry(100, new Ref("FILTER_DAYS", 1, 0)));

    public static void main(String[] args) throws Exception {
        String ip = System.getenv("GENVEX_IP");
        String email = System.getenv("GENVEX_EMAIL");
        if (ip == null || ip.isBlank() || email == null || email.isBlank()) {
            System.err.println("Set GENVEX_IP and GENVEX_EMAIL.");
            System.exit(2);
        }
        int max = option(args, "--max", 127);
        int retries = option(args, "--retries", 2);
        int watchMinutes = option(args, "--watch", 0);
        int everySeconds = option(args, "--every", 10);
        if (max < 0 || max > 255 || retries < 1 || watchMinutes < 0 || everySeconds < 1) {
            System.err.println("Usage: GenvexProbe [--max 0-255] [--retries N] [--watch MINUTES] [--every SECONDS]");
            System.exit(2);
        }

        GenvexClient client = new GenvexClient(ip, email);
        System.out.println("# GenvexProbe read-only dump " + now() + " unit " + ip + " max=" + max
                + " retries=" + retries);
        client.connect();
        int[] datapoints;
        int[] setpoints;
        try {
            System.out.println("kind,addr,status,raw,signed,genvexnabto,scaled,reply_hex");
            datapoints = scan(client, false, max, retries);
            setpoints = scan(client, true, max, retries);
        } finally {
            client.disconnect();
        }
        printSummary(datapoints, setpoints);
        if (watchMinutes > 0) {
            watch(client, retries, watchMinutes, everySeconds);
        }
    }

    private static int[] scan(GenvexClient client, boolean setpoint, int max, int retries)
            throws IOException, InterruptedException {
        int[] values = new int[max + 1];
        int misses = 0;
        for (int address = 0; address <= max; address++) {
            byte[] reply = request(client, setpoint, address, retries);
            if (reply == null && ++misses % 8 == 0) {
                // Eight silent addresses in a row may mean the session expired; a new one is one handshake.
                client.connect();
                reply = request(client, setpoint, address, retries);
            }
            if (reply != null) {
                misses = 0;
            }
            values[address] = printRow(setpoint, address, reply);
        }
        return values;
    }

    private static byte[] request(GenvexClient client, boolean setpoint, int address, int retries)
            throws IOException, InterruptedException {
        return setpoint ? client.readSetpointResponse(address, retries)
                : client.readDatapointResponse(address, retries);
    }

    private static int printRow(boolean setpoint, int address, byte[] reply) {
        Ref ref = (setpoint ? SETPOINTS : DATAPOINTS).get(address);
        int raw = decode(setpoint, reply);
        String status = reply == null ? "no-reply"
                : reply.length < (setpoint ? 5 : 4) ? "short"
                : raw < 0 ? "ffff" : "ok";
        boolean ok = raw >= 0;
        System.out.println(String.join(",", setpoint ? "SP" : "DP", String.valueOf(address), status,
                ok ? String.valueOf(raw) : "", ok ? String.valueOf((short) raw) : "",
                ref == null ? "" : ref.name(), ok && ref != null ? scaled(ref, setpoint, raw) : "", hex(reply)));
        return ok ? raw : NO_VALUE;
    }

    private static int decode(boolean setpoint, byte[] reply) {
        return setpoint ? GenvexClient.decodeSetpointValue(reply) : GenvexClient.decodeDatapointValue(reply);
    }

    private static void printSummary(int[] dp, int[] sp) {
        System.out.println("# --- summary: genvexnabto names and scaling, raw values in the rows above ---");
        int duty = at(dp, 18);
        int rpm = at(dp, 35);
        System.out.println("# stage from DP18 duty and DP35 rpm (nearest of 30/50/70/100 %): "
                + (duty == NO_VALUE || rpm == NO_VALUE ? "unknown" : HumidityMonitor.estimateFanSpeed(rpm, duty)));
        printLine("# fan setpoint read", sp, true, 7);
        printLine("# DP24 next to the temperatures", dp, false, 24, 20, 21, 22, 23);
        printLine("# unit functions", sp, true, 6, 30, 70, 1, 3);
        printLine("# heaters", dp, false, 41, 42);
        printLine("# bypass state", dp, false, 53, 40);
        printLine("# bypass settings", sp, true, 21, 29, 57, 58, 59);
        printLine("# supply levels 1-4", sp, true, 10, 11, 12, 8);
        printLine("# extract levels 1-4", sp, true, 13, 14, 15, 9);
        printLine("# alarms", dp, false, 114, 115);
        printLine("# filter", sp, true, 100);
    }

    private static void printLine(String title, int[] values, boolean setpoint, int... addresses) {
        StringBuilder line = new StringBuilder(title).append(':');
        for (int address : addresses) {
            Ref ref = (setpoint ? SETPOINTS : DATAPOINTS).get(address);
            int raw = at(values, address);
            line.append(' ').append(setpoint ? "SP" : "DP").append(address).append(' ').append(ref.name())
                    .append('=').append(raw == NO_VALUE ? "?" : scaled(ref, setpoint, raw));
        }
        System.out.println(line);
    }

    private static void watch(GenvexClient client, int retries, int minutes, int everySeconds)
            throws InterruptedException {
        System.out.println("# watch every " + everySeconds + " s for " + minutes + " min, raw values");
        System.out.println("time,dp18_supply_duty,dp35_supply_rpm,stage_from_duty,sp7,dp24,dp21,dp26,dp53");
        long end = System.currentTimeMillis() + minutes * 60_000L;
        while (System.currentTimeMillis() < end) {
            long started = System.currentTimeMillis();
            StringBuilder row = new StringBuilder(now());
            try {
                if (!client.isConnected()) {
                    client.connect();
                }
                int duty = GenvexClient.decodeDatapointValue(client.readDatapointResponse(18, retries));
                int rpm = GenvexClient.decodeDatapointValue(client.readDatapointResponse(35, retries));
                int[] rest = {
                        GenvexClient.decodeSetpointValue(client.readSetpointResponse(7, retries)),
                        GenvexClient.decodeDatapointValue(client.readDatapointResponse(24, retries)),
                        GenvexClient.decodeDatapointValue(client.readDatapointResponse(21, retries)),
                        GenvexClient.decodeDatapointValue(client.readDatapointResponse(26, retries)),
                        GenvexClient.decodeDatapointValue(client.readDatapointResponse(53, retries))};
                row.append(',').append(cell(duty)).append(',').append(cell(rpm)).append(',')
                        .append(duty >= 0 && rpm >= 0 ? String.valueOf(HumidityMonitor.estimateFanSpeed(rpm, duty)) : "");
                boolean missing = duty < 0 || rpm < 0;
                for (int value : rest) {
                    row.append(',').append(cell(value));
                    missing |= value < 0;
                }
                if (missing) {
                    client.disconnect();
                }
            } catch (IOException e) {
                row.append(",connect failed: ").append(e.getMessage());
                client.disconnect();
            }
            System.out.println(row);
            Thread.sleep(Math.max(0, everySeconds * 1000L - (System.currentTimeMillis() - started)));
        }
        client.disconnect();
    }

    private static String scaled(Ref ref, boolean setpoint, int raw) {
        // genvexnabto decodes datapoints as signed and setpoints as unsigned 16-bit values.
        int value = setpoint ? raw : (short) raw;
        if (ref.divider() == 1) {
            return String.valueOf(value + ref.offset());
        }
        return String.format(Locale.ROOT, "%.2f", (value + ref.offset()) / (double) ref.divider());
    }

    private static int at(int[] values, int address) {
        return address < values.length ? values[address] : NO_VALUE;
    }

    private static String cell(int value) {
        return value >= 0 ? String.valueOf(value) : "";
    }

    private static String hex(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(out.length() == 0 ? "" : " ").append(String.format("%02x", b & 0xFF));
        }
        return out.toString();
    }

    private static String now() {
        return OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static int option(String[] args, String name, int fallback) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].equals(name)) {
                return Integer.parseInt(args[i + 1]);
            }
        }
        return fallback;
    }
}
