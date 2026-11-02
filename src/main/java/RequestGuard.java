import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Optional DNS-rebinding and cross-site guard: a request must name an allowed host, and a state-changing request
 * must not come from a foreign page. Only installed when GENVEX_ALLOWED_HOSTS is set, so the add-on and Home
 * Assistant ingress behave exactly as before.
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
        String host = hostOf(exchange.getRequestHeaders().getFirst("Host"));
        if (host == null || !isAllowed(host)) {
            reject(exchange, 421, "Misdirected Request");
            return;
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !isSafeMethod(exchange.getRequestMethod()) && !isAllowed(hostOfOrigin(origin))) {
            reject(exchange, 403, "Forbidden");
            return;
        }
        chain.doFilter(exchange);
    }

    @Override
    public String description() {
        return "Host and Origin allow-list";
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

    /** Origin is scheme://host[:port]; the literal "null" (sandboxed or file pages) yields null and is refused. */
    static String hostOfOrigin(String origin) {
        try {
            String host = URI.create(origin.trim()).getHost();
            return host == null ? null : hostOf(host);
        } catch (IllegalArgumentException exception) {
            return null;
        }
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
