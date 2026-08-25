public class DebugRunner {
    public static void main(String[] args) {
        System.out.println("Starting Debug Runner for HumidityMonitor...");
        String ip = System.getenv().getOrDefault("GENVEX_IP", "192.168.1.100");
        String email = System.getenv().getOrDefault("GENVEX_EMAIL", "user@example.com");
        
        System.out.println("Using IP: " + ip);
        System.out.println("Using Email: " + email);

        HumidityMonitor monitor = new HumidityMonitor(ip, email);
        monitor.start();
        
        // Keep main thread alive? monitor.start() uses a scheduler, so it should be fine as long as threads are non-daemon.
        // ScheduledExecutorService threads are non-daemon by default? No, usually they are not.
        // Executors.newScheduledThreadPool(1) -> default thread factory -> non-daemon threads.
        // So the app should stay running.
    }
}
