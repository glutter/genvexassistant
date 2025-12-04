import java.io.IOException;

public class TestFanLevels {
    public static void main(String[] args) {
        String ip = System.getenv("GENVEX_IP");
        String email = System.getenv("GENVEX_EMAIL");

        if (ip == null || email == null) {
            System.out.println("Please set GENVEX_IP and GENVEX_EMAIL environment variables.");
            // Fallback for manual testing if needed, but better to rely on env
            return;
        }

        GenvexClient client = new GenvexClient(ip, email);

        try {
            System.out.println("Connecting to " + ip + "...");
            client.connect();
            System.out.println("Connected!");

            System.out.println("\n--- Scanning Addresses 0-100 for Percentage-like values (20-100) ---");
            for (int i = 0; i <= 100; i++) {
                try {
                    int val = client.readDatapoint(i);
                    if (val >= 20 && val <= 100) {
                        System.out.println("Addr " + i + ": " + val);
                    }
                } catch (Exception e) {
                    // Ignore read errors
                }
            }
            
            System.out.println("\n--- Checking Write Addresses from Optima270 Reference ---");
            int[] checkAddrs = {30, 32, 34, 26, 36, 38, 40, 28};
            for (int addr : checkAddrs) {
                try {
                    int val = client.readDatapoint(addr);
                    System.out.println("Addr " + addr + ": " + val);
                } catch (Exception e) {
                    System.out.println("Addr " + addr + ": Error");
                }
            }

            client.disconnect();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
