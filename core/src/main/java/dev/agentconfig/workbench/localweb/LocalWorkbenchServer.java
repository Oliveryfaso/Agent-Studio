package dev.agentconfig.workbench.localweb;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loopback-only transport for the local workbench UI. */
public final class LocalWorkbenchServer implements AutoCloseable {
    private static final int MAX_BODY_BYTES = 48 * 1024;
    private final HttpServer server;
    private final ExecutorService executor;
    private final SkillChangeHttpApi api;
    private final String token;
    private final String origin;

    private LocalWorkbenchServer(HttpServer server, ExecutorService executor,
            SkillChangeHttpApi api, String token) {
        this.server = server;
        this.executor = executor;
        this.api = api;
        this.token = token;
        this.origin = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public static LocalWorkbenchServer start(Path suppliedStateRoot) throws IOException {
        Path stateRoot = suppliedStateRoot.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(stateRoot)) throw new IOException("state root link");
        stateRoot = stateRoot.toRealPath();
        if (!Files.isDirectory(stateRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("state root directory");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(
                InetAddress.getByName("127.0.0.1"), 0), 16);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        byte[] tokenBytes = new byte[32];
        new SecureRandom().nextBytes(tokenBytes);
        LocalWorkbenchServer workbench = new LocalWorkbenchServer(server, executor,
                new SkillChangeHttpApi(stateRoot), HexFormat.of().formatHex(tokenBytes));
        server.createContext("/api/v1/runtime", workbench::runtime);
        server.createContext("/api/v1/skill-changes/preview",
                workbench.post(workbench.api::preview));
        server.createContext("/api/v1/skill-changes/apply",
                workbench.post(workbench.api::apply));
        server.createContext("/api/v1/skill-changes/rollback",
                workbench.post(workbench.api::rollback));
        server.createContext("/", workbench::notFound);
        server.setExecutor(executor);
        server.start();
        return workbench;
    }

    public URI baseUri() {
        return URI.create(origin + "/");
    }

    public String sessionToken() {
        return token;
    }

    private HttpHandler post(ApiCall call) {
        return exchange -> {
            try {
                handlePost(exchange, call);
            } catch (RuntimeException exception) {
                try {
                    sendError(exchange, 500, "INTERNAL_ERROR", true);
                } catch (IOException ignored) {
                    // The client may already have disconnected; never expose the exception.
                }
            } finally {
                exchange.close();
            }
        };
    }

    private void handlePost(HttpExchange exchange, ApiCall call) throws IOException {
            if (!validHost(exchange)) {
                sendError(exchange, 403, "HOST_FORBIDDEN", false);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "METHOD_NOT_ALLOWED", false);
                return;
            }
            if (!origin.equals(exchange.getRequestHeaders().getFirst("Origin"))) {
                sendError(exchange, 403, "ORIGIN_FORBIDDEN", false);
                return;
            }
            if (!("Bearer " + token).equals(
                    exchange.getRequestHeaders().getFirst("Authorization"))) {
                sendError(exchange, 401, "SESSION_TOKEN_INVALID", false);
                return;
            }
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (!validJsonContentType(contentType)) {
                sendError(exchange, 415, "MEDIA_TYPE_UNSUPPORTED", false);
                return;
            }
            byte[] body;
            try {
                body = readBounded(exchange.getRequestBody());
            } catch (BodyTooLarge exception) {
                sendError(exchange, 413, "BODY_TOO_LARGE", false);
                return;
            }
            String requestId = UUID.randomUUID().toString();
            Map<String, Object> request;
            try {
                request = FlatJsonObjectParser.parse(strictUtf8(body));
            } catch (IllegalArgumentException | CharacterCodingException exception) {
                sendError(exchange, 400, "JSON_INVALID", false, requestId);
                return;
            }
            SkillChangeHttpApi.ApiResponse response = call.invoke(request, requestId);
            send(exchange, response.statusCode(), response.body());
    }

    private static boolean validJsonContentType(String contentType) {
        return contentType != null && ("application/json".equalsIgnoreCase(contentType.trim())
                || "application/json; charset=utf-8".equalsIgnoreCase(contentType.trim()));
    }

    private void runtime(HttpExchange exchange) throws IOException {
        if (!validHost(exchange)) {
            sendError(exchange, 403, "HOST_FORBIDDEN", false);
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "METHOD_NOT_ALLOWED", false);
            return;
        }
        send(exchange, 200, "{\n  \"schemaVersion\": 1,\n  \"status\": \"READY\",\n"
                + "  \"productVersion\": \"0.1.0-lab\",\n"
                + "  \"capabilities\": [{\"hostId\": \"codex\","
                + " \"existingSkillReplace\": true}]\n}");
    }

    private void notFound(HttpExchange exchange) throws IOException {
        sendError(exchange, 404, "NOT_FOUND", false);
    }

    private boolean validHost(HttpExchange exchange) {
        return ("127.0.0.1:" + server.getAddress().getPort())
                .equals(exchange.getRequestHeaders().getFirst("Host"));
    }

    private static byte[] readBounded(InputStream input) throws IOException, BodyTooLarge {
        byte[] bytes = input.readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES || input.read() != -1) throw new BodyTooLarge();
        return bytes;
    }

    private static String strictUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }

    private static void sendError(HttpExchange exchange, int status, String code,
            boolean retryable) throws IOException {
        sendError(exchange, status, code, retryable, UUID.randomUUID().toString());
    }

    private static void sendError(HttpExchange exchange, int status, String code,
            boolean retryable, String requestId) throws IOException {
        send(exchange, status, "{\n  \"schemaVersion\": 1,\n  \"requestId\": "
                + SkillChangeHttpApi.json(requestId) + ",\n  \"error\": {\"code\": "
                + SkillChangeHttpApi.json(code) + ", \"retryable\": " + retryable + "}\n}");
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    @FunctionalInterface
    private interface ApiCall {
        SkillChangeHttpApi.ApiResponse invoke(Map<String, Object> request, String requestId);
    }

    private static final class BodyTooLarge extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
