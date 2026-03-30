package company.vk.edu.distrib.compute.alan;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import company.vk.edu.distrib.compute.Dao;
import company.vk.edu.distrib.compute.KVService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

class AlanKVService implements KVService {
    private static final Logger log = LoggerFactory.getLogger(AlanKVService.class);

    private final int port;
    private final Dao<byte[]> dao;
    private final HttpServer server;
    private boolean started;

    public AlanKVService(int port, Dao<byte[]> dao) throws IOException {
        this.port = port;
        this.dao = Objects.requireNonNull(dao, "dao must not be null");
        this.server = HttpServer.create();

        server.createContext("/v0/status", wrap(this::handleStatus));
        server.createContext("/v0/entity", wrap(this::handleEntity));

        if (log.isInfoEnabled()) {
            log.info("AlanKVService created for port {}", port);
        }
    }

    @Override
    public void start() {
        if (started) {
            throw new IllegalStateException("Service already started");
        }
        try {
            InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
            server.bind(address, 0);
            if (log.isInfoEnabled()) {
                log.info("HTTP server bound to {}", server.getAddress());
            }

            server.start();
            started = true;

            if (log.isInfoEnabled()) {
                log.info("HTTP server started on {}", server.getAddress());
            }
        } catch (IOException e) {
            log.error("Failed to start HTTP server on port {}", port, e);
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void stop() {
        if (!started) {
            throw new IllegalStateException("Service is not started");
        }

        log.info("Stopping HTTP server on port {}", port);
        server.stop(0);

        try {
            dao.close();
            log.info("DAO closed successfully");
        } catch (IOException e) {
            log.error("Failed to close DAO", e);
            throw new UncheckedIOException(e);
        }

        started = false;
        log.info("HTTP server stopped");
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        exchange.sendResponseHeaders(200, -1);
    }

    private void handleEntity(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange);
        String id = params.get("id");

        if (id == null || id.isBlank()) {
            exchange.sendResponseHeaders(400, -1);
            return;
        }

        switch (exchange.getRequestMethod()) {
            case "GET" -> {
                byte[] value = dao.get(id);
                exchange.sendResponseHeaders(200, value.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(value);
                }
            }
            case "PUT" -> {
                byte[] value = exchange.getRequestBody().readAllBytes();
                dao.upsert(id, value);
                exchange.sendResponseHeaders(201, -1);
            }
            case "DELETE" -> {
                dao.delete(id);
                exchange.sendResponseHeaders(202, -1);
            }
            default -> exchange.sendResponseHeaders(405, -1);
        }
    }

    private HttpHandler wrap(HttpHandler handler) {
        return exchange -> {
            try (exchange) {
                try {
                    if (log.isDebugEnabled()) {
                        log.debug("Handling {} {}", exchange.getRequestMethod(), exchange.getRequestURI());
                    }
                    handler.handle(exchange);
                } catch (NoSuchElementException e) {
                    sendError(exchange, 404, e.getMessage(), e);
                } catch (IllegalArgumentException e) {
                    sendError(exchange, 400, e.getMessage(), e);
                } catch (Exception e) {
                    sendError(exchange, 503, e.getMessage(), e);
                }
            }
        };
    }

    private static Map<String, String> parseQuery(HttpExchange exchange) {
        String rawQuery = exchange.getRequestURI().getRawQuery();
        Map<String, String> params = new ConcurrentHashMap<>();

        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }

        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length > 1
                    ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                    : "";
            params.put(key, value);
        }

        return params;
    }

    private void sendError(HttpExchange exchange, int code, String text, Exception exception) throws IOException {
        if (code >= 500) {
            if (log.isErrorEnabled()) {
                log.error(
                        "Request {} {} failed with {}",
                        exchange.getRequestMethod(),
                        exchange.getRequestURI(),
                        code,
                        exception
                );
            }
        } else {
            if (log.isWarnEnabled()) {
                log.warn(
                        "Request {} {} failed with {}: {}",
                        exchange.getRequestMethod(),
                        exchange.getRequestURI(),
                        code,
                        text
                );
            }
        }

        byte[] body = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
