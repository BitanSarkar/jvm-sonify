package net.bitsar.sonify.scenarios.upstream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A stand-in for a third-party API, run as its own process.
 *
 * <p>A separate process on a real socket matters: a slow dependency is only interesting because
 * the caller's thread is held in a socket read it cannot escape, and calling yourself on the same
 * server would share a thread pool and hide exactly that.
 *
 * <pre>
 * java -cp sonify-scenarios/target/classes net.bitsar.sonify.scenarios.upstream.UpstreamServer 8090
 * </pre>
 */
public final class UpstreamServer {

    private static final int DEFAULT_PORT = 8090;
    /** Generous, because the slow and blackhole paths hold a handler thread for their duration. */
    private static final int HANDLER_THREADS = 400;
    /** Finite so a demo of a missing timeout eventually recovers instead of wedging the machine. */
    private static final long BLACKHOLE_CAP_MS = 90_000;

    private UpstreamServer() {
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 1024);
        server.setExecutor(Executors.newFixedThreadPool(HANDLER_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "upstream-handler");
            thread.setDaemon(true);
            return thread;
        }));

        server.createContext("/fast", exchange -> respond(exchange, 200, "{\"ok\":true}"));
        server.createContext("/slow", UpstreamServer::slow);
        server.createContext("/flaky", UpstreamServer::flaky);
        server.createContext("/huge", UpstreamServer::huge);
        server.createContext("/blackhole", UpstreamServer::blackhole);
        server.createContext("/health", exchange -> respond(exchange, 200, "upstream ok"));

        server.start();
        System.out.println("upstream listening on http://localhost:" + port);
        System.out.println("  /fast  /slow?ms=2000  /flaky?failPercent=70  /huge?mb=5  /blackhole");
    }

    /** Answers, but only after a delay. The ordinary "the vendor is having a bad day" case. */
    private static void slow(HttpExchange exchange) throws IOException {
        long millis = longParam(exchange, "ms", 2000, 0, 60_000);
        sleep(millis);
        respond(exchange, 200, "{\"ok\":true,\"delayedMs\":" + millis + "}");
    }

    /** Fails a configurable share of the time, which is what makes a retry policy dangerous. */
    private static void flaky(HttpExchange exchange) throws IOException {
        long failPercent = longParam(exchange, "failPercent", 70, 0, 100);
        sleep(longParam(exchange, "ms", 120, 0, 10_000));
        if (ThreadLocalRandom.current().nextInt(100) < failPercent) {
            respond(exchange, 503, "{\"error\":\"upstream unavailable\"}");
        } else {
            respond(exchange, 200, "{\"ok\":true}");
        }
    }

    /** Answers with megabytes, because somebody added a field that expanded a response. */
    private static void huge(HttpExchange exchange) throws IOException {
        int megabytes = (int) longParam(exchange, "mb", 5, 1, 50);
        StringBuilder body = new StringBuilder(megabytes * 1024 * 1024 + 64);
        body.append("{\"items\":[");
        String row = "{\"sku\":\"SKU-00000\",\"description\":\"" + "x".repeat(160) + "\"},";
        int rows = megabytes * 1024 * 1024 / row.length();
        for (int index = 0; index < rows; index++) {
            body.append(row);
        }
        body.setLength(body.length() - 1);
        body.append("]}");
        respond(exchange, 200, body.toString());
    }

    /** Accepts the connection and never answers. A client without a read timeout waits forever. */
    private static void blackhole(HttpExchange exchange) throws IOException {
        sleep(BLACKHOLE_CAP_MS);
        respond(exchange, 504, "{\"error\":\"took too long\"}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static long longParam(HttpExchange exchange, String name, long fallback, long min, long max) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return fallback;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                try {
                    return Math.max(min, Math.min(max, Long.parseLong(pair.substring(equals + 1))));
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Endpoints, so callers do not hard-code strings. */
    public static final Map<String, String> PATHS = Map.of(
            "fast", "/fast",
            "slow", "/slow",
            "flaky", "/flaky",
            "huge", "/huge",
            "blackhole", "/blackhole");
}
