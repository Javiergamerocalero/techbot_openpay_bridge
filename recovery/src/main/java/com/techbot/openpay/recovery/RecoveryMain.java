package com.techbot.openpay.recovery;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Standalone recovery API for the Windows TotalPosBridge service.
 *
 * This process is intentionally independent from totalpos-bridge.jar so it
 * remains available when the bridge JVM or TotalPOS SDK is hung.
 *
 * Endpoints:
 *   GET  /health          - recovery service liveness
 *   GET  /openpay/status  - bridge readiness check (API key required)
 *   POST /openpay/recover - restart TotalPosBridge only when unhealthy
 */
public final class RecoveryMain {

    private static final String VERSION = "1.0.0";
    private static final AtomicBoolean recoveryInProgress = new AtomicBoolean(false);
    private static final AtomicLong lastRestartEpochMs = new AtomicLong(0);

    private final Config config;
    private final HttpClient http;

    private RecoveryMain(Config config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.bridgeConnectTimeoutMs))
                .build();
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.load();
        RecoveryMain app = new RecoveryMain(config);
        app.start();
    }

    private void start() throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(config.bindAddress, config.port), 0);
        server.createContext("/health", new HealthHandler());
        server.createContext("/openpay/status", new StatusHandler());
        server.createContext("/openpay/recover", new RecoverHandler());
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();

        log("INFO", "TECHBOT Openpay Recovery Service v" + VERSION
                + " listening on " + config.bindAddress + ":" + config.port
                + "; bridge=" + config.bridgeHealthUrl
                + "; service=" + config.windowsServiceName);
    }

    private final class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                methodNotAllowed(exchange);
                return;
            }
            json(exchange, 200,
                    "{\"ok\":true,\"service\":\"TechbotOpenpayRecovery\",\"version\":\"" + VERSION + "\"}");
        }
    }

    private final class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                methodNotAllowed(exchange);
                return;
            }
            if (!authorized(exchange)) {
                unauthorized(exchange);
                return;
            }

            BridgeHealth health = checkBridgeHealth();
            json(exchange, health.healthy ? 200 : 503, health.toJson(false, false, null));
        }
    }

    private final class RecoverHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                methodNotAllowed(exchange);
                return;
            }
            if (!authorized(exchange)) {
                unauthorized(exchange);
                return;
            }

            if (!recoveryInProgress.compareAndSet(false, true)) {
                json(exchange, 409, "{\"ok\":false,\"error\":\"recovery_in_progress\"}");
                return;
            }

            long started = System.currentTimeMillis();
            try {
                BridgeHealth before = checkBridgeHealth();
                if (before.healthy) {
                    json(exchange, 200, before.toJson(false, false, "bridge_already_healthy"));
                    return;
                }

                long lastRestart = lastRestartEpochMs.get();
                long cooldownMs = TimeUnit.SECONDS.toMillis(config.restartCooldownSeconds);
                if (lastRestart > 0 && System.currentTimeMillis() - lastRestart < cooldownMs) {
                    long remaining = Math.max(1, (cooldownMs - (System.currentTimeMillis() - lastRestart)) / 1000);
                    json(exchange, 429,
                            before.toJson(true, false, "restart_cooldown_" + remaining + "s"));
                    return;
                }

                log("WARN", "Bridge unhealthy; starting controlled recovery. reason=" + before.reason);
                ServiceResult restart = restartWindowsService();
                if (!restart.ok) {
                    log("ERROR", "Service restart failed: " + restart.message);
                    json(exchange, 500,
                            before.toJson(true, true, "service_restart_failed:" + escape(restart.message)));
                    return;
                }

                lastRestartEpochMs.set(System.currentTimeMillis());
                BridgeHealth after = waitUntilHealthy();
                long duration = System.currentTimeMillis() - started;

                if (after.healthy) {
                    log("INFO", "Recovery successful in " + duration + "ms");
                    json(exchange, 200,
                            after.toJson(true, true, "recovered")
                                    .replace("}", ",\"duration_ms\":" + duration + "}"));
                } else {
                    log("ERROR", "Bridge still unhealthy after restart. reason=" + after.reason);
                    json(exchange, 503,
                            after.toJson(true, true, "bridge_unhealthy_after_restart")
                                    .replace("}", ",\"duration_ms\":" + duration + "}"));
                }
            } finally {
                recoveryInProgress.set(false);
            }
        }
    }

    private boolean authorized(HttpExchange exchange) {
        String supplied = exchange.getRequestHeaders().getFirst("X-Techbot-Recovery-Key");
        return supplied != null && constantTimeEquals(config.apiKey, supplied);
    }

    private BridgeHealth checkBridgeHealth() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.bridgeHealthUrl))
                    .timeout(Duration.ofMillis(config.bridgeReadTimeoutMs))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body() == null ? "" : response.body();
            boolean sdkInitialized = containsJsonTrue(body, "sdkInitialized");
            boolean statusOk = containsJsonString(body, "status", "OK");
            boolean healthy = response.statusCode() >= 200 && response.statusCode() < 300
                    && sdkInitialized && statusOk;
            String reason = healthy ? "healthy"
                    : "http_" + response.statusCode() + "_sdkInitialized_" + sdkInitialized;
            return new BridgeHealth(healthy, true, response.statusCode(), sdkInitialized, reason);
        } catch (java.net.http.HttpTimeoutException e) {
            return new BridgeHealth(false, false, 0, false, "timeout");
        } catch (java.net.ConnectException e) {
            return new BridgeHealth(false, false, 0, false, "connection_refused");
        } catch (Exception e) {
            return new BridgeHealth(false, false, 0, false,
                    "health_check_error:" + e.getClass().getSimpleName());
        }
    }

    private BridgeHealth waitUntilHealthy() {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(config.recoveryWaitSeconds);
        BridgeHealth last = new BridgeHealth(false, false, 0, false, "not_checked");
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(config.healthPollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new BridgeHealth(false, false, 0, false, "interrupted");
            }
            last = checkBridgeHealth();
            if (last.healthy) {
                return last;
            }
        }
        return last;
    }

    private ServiceResult restartWindowsService() {
        ServiceResult stop = runFixedCommand("sc.exe", "stop", config.windowsServiceName);
        if (!stop.ok && !stop.message.toLowerCase(Locale.ROOT).contains("not started")
                && !stop.message.contains("1062")) {
            // A stopped service is acceptable; any other stop error is relevant.
            log("WARN", "Stop returned non-zero: " + stop.message);
        }

        waitForServiceState("STOPPED", config.serviceStopWaitSeconds);

        ServiceResult start = runFixedCommand("sc.exe", "start", config.windowsServiceName);
        if (!start.ok) {
            return start;
        }

        if (!waitForServiceState("RUNNING", config.serviceStartWaitSeconds)) {
            return new ServiceResult(false, "service_did_not_reach_RUNNING");
        }
        return new ServiceResult(true, "service_running");
    }

    private boolean waitForServiceState(String expected, int seconds) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds);
        while (System.currentTimeMillis() < deadline) {
            ServiceResult result = runFixedCommand("sc.exe", "query", config.windowsServiceName);
            if (result.message.contains("STATE") && result.message.contains(expected)) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private ServiceResult runFixedCommand(String... command) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            boolean exited = process.waitFor(config.commandTimeoutSeconds, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                return new ServiceResult(false, "command_timeout");
            }
            return new ServiceResult(process.exitValue() == 0, output);
        } catch (Exception e) {
            return new ServiceResult(false, e.getClass().getSimpleName() + ":" + e.getMessage());
        }
    }

    private static boolean containsJsonTrue(String body, String key) {
        String compact = body.replaceAll("\\s+", "");
        return compact.contains("\"" + key + "\":true");
    }

    private static boolean containsJsonString(String body, String key, String value) {
        String compact = body.replaceAll("\\s+", "");
        return compact.contains("\"" + key + "\":\"" + value + "\"");
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = supplied.getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void unauthorized(HttpExchange exchange) throws IOException {
        json(exchange, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
    }

    private static void methodNotAllowed(HttpExchange exchange) throws IOException {
        json(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", " ").replace("\n", " ");
    }

    private static void log(String level, String message) {
        System.out.println(Instant.now() + " " + level + " " + message);
    }

    private record ServiceResult(boolean ok, String message) {}

    private record BridgeHealth(boolean healthy, boolean reachable, int httpStatus,
                                boolean sdkInitialized, String reason) {
        String toJson(boolean restartRequired, boolean restartExecuted, String message) {
            return "{"
                    + "\"ok\":" + healthy
                    + ",\"restart_required\":" + restartRequired
                    + ",\"restart_executed\":" + restartExecuted
                    + ",\"bridge_reachable\":" + reachable
                    + ",\"http_status\":" + httpStatus
                    + ",\"sdk_initialized\":" + sdkInitialized
                    + ",\"reason\":\"" + escape(reason) + "\""
                    + (message == null ? "" : ",\"message\":\"" + escape(message) + "\"")
                    + "}";
        }
    }

    private static final class Config {
        final String bindAddress;
        final int port;
        final String apiKey;
        final String bridgeHealthUrl;
        final String windowsServiceName;
        final int bridgeConnectTimeoutMs;
        final int bridgeReadTimeoutMs;
        final int restartCooldownSeconds;
        final int recoveryWaitSeconds;
        final int healthPollIntervalMs;
        final int commandTimeoutSeconds;
        final int serviceStopWaitSeconds;
        final int serviceStartWaitSeconds;

        private Config(Properties p) {
            bindAddress = p.getProperty("bindAddress", "0.0.0.0").trim();
            port = Integer.parseInt(p.getProperty("port", "9092"));
            apiKey = requireSecureApiKey(p.getProperty("apiKey", ""));
            bridgeHealthUrl = p.getProperty("bridgeHealthUrl", "http://127.0.0.1:9090/api/health").trim();
            windowsServiceName = p.getProperty("windowsServiceName", "TotalPosBridge").trim();
            bridgeConnectTimeoutMs = Integer.parseInt(p.getProperty("bridgeConnectTimeoutMs", "1500"));
            bridgeReadTimeoutMs = Integer.parseInt(p.getProperty("bridgeReadTimeoutMs", "3000"));
            restartCooldownSeconds = Integer.parseInt(p.getProperty("restartCooldownSeconds", "120"));
            recoveryWaitSeconds = Integer.parseInt(p.getProperty("recoveryWaitSeconds", "30"));
            healthPollIntervalMs = Integer.parseInt(p.getProperty("healthPollIntervalMs", "1000"));
            commandTimeoutSeconds = Integer.parseInt(p.getProperty("commandTimeoutSeconds", "10"));
            serviceStopWaitSeconds = Integer.parseInt(p.getProperty("serviceStopWaitSeconds", "10"));
            serviceStartWaitSeconds = Integer.parseInt(p.getProperty("serviceStartWaitSeconds", "15"));
        }

        static Config load() throws IOException {
            Path path = Path.of(System.getProperty("recovery.config", "recovery.properties"));
            if (!Files.isRegularFile(path)) {
                throw new IOException("Missing recovery configuration: " + path.toAbsolutePath());
            }
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(path)) {
                p.load(in);
            }
            return new Config(p);
        }

        private static String requireSecureApiKey(String value) {
            String key = value == null ? "" : value.trim();
            if (key.length() < 24 || "CHANGE_ME".equalsIgnoreCase(key)) {
                throw new IllegalArgumentException("apiKey must be changed and contain at least 24 characters");
            }
            return key;
        }
    }
}
