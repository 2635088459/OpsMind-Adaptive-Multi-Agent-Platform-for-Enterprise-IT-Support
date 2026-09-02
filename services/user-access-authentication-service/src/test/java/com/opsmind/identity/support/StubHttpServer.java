package com.opsmind.identity.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * A minimal, dependency-free (JDK-only) HTTP stub for tests that need a
 * real OIDC discovery endpoint and/or JWKS endpoint to make a real HTTP
 * call against — SPEC-UA-004's own discovery client and algorithm
 * restriction logic are only meaningfully tested end-to-end over the wire,
 * not by mocking the HTTP layer away. The port is bound (and so {@link
 * #baseUrl()} known) before any route is registered, so a discovery
 * document's own {@code jwks_uri} can point back at this same server.
 */
public final class StubHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final String baseUrl;

    private StubHttpServer(HttpServer server) {
        this.server = server;
        this.baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    public static StubHttpServer create() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(Executors.newSingleThreadExecutor());
            return new StubHttpServer(server);
        } catch (IOException e) {
            throw new IllegalStateException("failed to create stub HTTP server", e);
        }
    }

    public static StubHttpServer startWithJsonRoutes(Map<String, String> pathToJsonBody) {
        StubHttpServer stub = create();
        pathToJsonBody.forEach((path, body) -> stub.route(path, () -> body));
        stub.start();
        return stub;
    }

    public StubHttpServer route(String path, Supplier<String> jsonBody) {
        server.createContext(path, exchange -> {
            byte[] bytes = jsonBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        return this;
    }

    /**
     * Routes on a request header value (e.g. Tempo's real {@code
     * X-Scope-OrgID} multi-tenancy header, SPEC-SC-014/SPEC-OP-031) rather
     * than path — a header value with no entry in {@code
     * headerValueToJsonBody} answers a real 404 with an empty body, mirroring
     * Tempo's own real "tenant queried, trace not found under it" response.
     */
    public StubHttpServer routeByHeader(String path, String headerName, Map<String, String> headerValueToJsonBody) {
        server.createContext(path, exchange -> {
            String headerValue = exchange.getRequestHeaders().getFirst(headerName);
            String body = headerValue == null ? null : headerValueToJsonBody.get(headerValue);
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        return this;
    }

    public StubHttpServer start() {
        server.start();
        return this;
    }

    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
