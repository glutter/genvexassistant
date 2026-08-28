public class DebugRunner {
    public static void main(String[] args) {
        System.out.println("Starting Debug Runner for HumidityMonitor...");
        String ip = System.getenv().getOrDefault("GENVEX_IP", "192.168.1.100");
        String email = System.getenv().getOrDefault("GENVEX_EMAIL", "user@example.com");

        HumidityMonitor monitor = new HumidityMonitor(ip, email);
        monitor.start();
    }
}
