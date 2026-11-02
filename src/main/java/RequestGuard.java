import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Keeps other web pages from driving the fan through the user's browser. A state-changing request must come from the
 * page this server served: its {@code Origin} has to match its {@code Host}, and a browser that says the request is
 * cross-site is refused. With a host allow-list (GENVEX_ALLOWED_HOSTS) the {@code Host} must also be on it, which stops
 * DNS rebinding. Without the list the Host is not checked, and the add-on is not given this guard at all, so Home
 * Assistant ingress behaves exactly as before.
 */
final class RequestGuard extends Filter {
    // A page served from loopback cannot be an attacker's: a rebinding attack arrives under the attacker's own name.
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1");

    private final Set<String> allowedHosts;

    RequestGuard(Set<String> allowedHosts) {
        this.allowedHosts = allowedHosts;
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        String hostHeader = exchange.getRequestHeaders().getFirst("Host");
        if (!allowedHosts.isEmpty() && !isAllowed(hostOf(hostHeader))) {
            reject(exchange, 421, "Misdirected Request");
            return;
        }
        if (!isSafeMethod(exchange.getRequestMethod())) {
            String fetchSite = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            boolean crossSite = fetchSite != null
                    && ("cross-site".equalsIgnoreCase(fetchSite) || "same-site".equalsIgnoreCase(fetchSite));
            if (crossSite || (origin != null && !originMatchesHost(origin, hostHeader))) {
                reject(exchange, 403, "Forbidden");
                return;
            }
        }
        chain.doFilter(exchange);
    }

    @Override
    public String description() {
        return "Host allow-list and same-origin check for writes";
    }

    private boolean isAllowed(String host) {
        return host != null && (allowedHosts.contains(host) || LOOPBACK_HOSTS.contains(host));
    }

    private static boolean isSafeMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method);
    }

    /** Host header value without port or IPv6 brackets, lower-cased; null when absent or unparseable. */
    static String hostOf(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) return null;
        String value = hostHeader.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end < 0 ? null : value.substring(1, end);
        }
        int colon = value.indexOf(':');
        String host = colon < 0 ? value : value.substring(0, colon);
        return host.isEmpty() ? null : host;
    }

    /** Port of a Host header, or -1 when it has none (or the value is unparseable). */
    private static int portOf(String hostHeader) {
        if (hostHeader == null) return -1;
        String value = hostHeader.trim();
        int colon = value.startsWith("[") ? value.indexOf("]:") + 1 : value.indexOf(':');
        if (colon <= 0) return -1;
        try {
            return Integer.parseInt(value.substring(colon + 1));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    /** Origin is scheme://host[:port]; the literal "null" (sandboxed or file pages) matches nothing. */
    static boolean originMatchesHost(String origin, String hostHeader) {
        String host = hostOf(hostHeader);
        if (host == null) return false;
        URI uri;
        try {
            uri = URI.create(origin.trim());
        } catch (IllegalArgumentException exception) {
            return false;
        }
        if (uri.getHost() == null || !host.equals(hostOf(uri.getHost()))) return false;
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        if (!https && !"http".equalsIgnoreCase(uri.getScheme())) return false;
        int originPort = uri.getPort() >= 0 ? uri.getPort() : https ? 443 : 80;
        // A Host without a port means the scheme's default port, as behind a TLS-terminating proxy.
        int hostPort = portOf(hostHeader);
        return hostPort >= 0 ? hostPort == originPort : originPort == (https ? 443 : 80);
    }

    private static void reject(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = ("{\"error\": \"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
