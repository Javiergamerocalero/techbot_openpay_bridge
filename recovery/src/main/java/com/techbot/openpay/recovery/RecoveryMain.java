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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final String VERSION = "1.0.3";

    /**
     * Unico servicio Windows que este proceso puede tocar. Va fijo en el codigo
     * a proposito: si saliera de la configuracion, quien pudiera editar el
     * archivo podria reiniciar cualquier servicio de la maquina.
     */
    private static final String TOTALPOS_SERVICE = "TotalPosBridge";
    private static final int SERVICE_STOPPED = 1;
    private static final int SERVICE_RUNNING = 4;
    private static final Pattern SC_STATE_LINE = Pattern.compile("^\\s*[^:]+:\\s*([1-7])(?:\\s|$).*$");
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

        scheduleStartupSelfCheck();

        log("INFO", "TECHBOT Openpay Recovery Service v" + VERSION
                + " listening on " + config.bindAddress + ":" + config.port
                + "; bridge=" + config.bridgeHealthUrl
                + "; service=" + TOTALPOS_SERVICE);
    }

    /**
     * One-shot startup self-check. This is intentionally not a watchdog:
     * after the configured grace period it checks Bridge health once and,
     * only if unhealthy, performs one controlled recovery attempt.
     */
    private void scheduleStartupSelfCheck() {
        if (!config.startupSelfCheckEnabled) {
            log("INFO", "Startup self-check disabled");
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                log("INFO", "Startup self-check: waiting up to "
                        + config.startupGraceSeconds + "s for the Bridge to come up");
                BridgeHealth health = awaitBridgeStartup();
                if (health.healthy) {
                    log("INFO", "Startup self-check: Bridge healthy; no action required");
                    return;
                }
                log("WARN", "Startup self-check: Bridge did not come up within "
                        + config.startupGraceSeconds + "s; attempting one controlled recovery. reason="
                        + health.reason);
                performStartupRecovery();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log("WARN", "Startup self-check interrupted");
            } catch (Exception e) {
                log("ERROR", "Startup self-check failed: " + e.getClass().getSimpleName() + ":" + e.getMessage());
            }
        }, "openpay-startup-selfcheck");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Waits for the Bridge to come up, polling until the grace period ends and
     * returning as soon as it is healthy. Polling instead of sleeping blindly
     * matters on a cold boot: the SDK/PinPad initialisation can take longer
     * than the window on one machine and less on another, and restarting a
     * Bridge that is still initialising hits it at the worst possible moment.
     * Because it returns early, a generous window costs nothing when the boot
     * was normal.
     */
    private BridgeHealth awaitBridgeStartup() throws InterruptedException {
        long deadline = System.currentTimeMillis()
                + TimeUnit.SECONDS.toMillis(config.startupGraceSeconds);
        BridgeHealth last = checkBridgeHealth();
        while (!last.healthy && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(config.healthPollIntervalMs);
            last = checkBridgeHealth();
        }
        return last;
    }

    private void performStartupRecovery() {
        if (!recoveryInProgress.compareAndSet(false, true)) {
            log("WARN", "Startup self-check skipped: another recovery is already in progress");
            return;
        }
        long started = System.currentTimeMillis();
        try {
            BridgeHealth before = checkBridgeHealth();
            if (before.healthy) {
                log("INFO", "Startup recovery skipped: Bridge became healthy");
                return;
            }
            // Mismo enfriamiento que la ruta bajo demanda: si el quiosco ya
            // pidio recuperacion durante la ventana de gracia, el arranque no
            // puede reiniciar otra vez encima de ese intento.
            long now = System.currentTimeMillis();
            if (cooldownActive(lastRestartEpochMs.get(), now, config.restartCooldownSeconds)) {
                log("WARN", "Startup recovery skipped: restart cooldown active ("
                        + cooldownRemainingSeconds(lastRestartEpochMs.get(), now,
                                config.restartCooldownSeconds) + "s remaining)");
                return;
            }
            lastRestartEpochMs.set(System.currentTimeMillis());
            ServiceResult restart = restartWindowsService();
            if (!restart.ok) {
                log("ERROR", "Startup recovery service restart failed: " + restart.message);
                return;
            }
            BridgeHealth after = waitUntilHealthy();
            long duration = System.currentTimeMillis() - started;
            if (after.healthy) {
                log("INFO", "Startup recovery successful in " + duration + "ms");
            } else {
                log("ERROR", "Bridge still unhealthy after startup recovery. reason=" + after.reason);
            }
        } finally {
            recoveryInProgress.set(false);
        }
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

                long now = System.currentTimeMillis();
                long lastRestart = lastRestartEpochMs.get();
                if (cooldownActive(lastRestart, now, config.restartCooldownSeconds)) {
                    long remaining = cooldownRemainingSeconds(lastRestart, now,
                            config.restartCooldownSeconds);
                    json(exchange, 429,
                            before.toJson(true, false, "restart_cooldown_" + remaining + "s"));
                    return;
                }

                log("WARN", "Bridge unhealthy; starting controlled recovery. reason=" + before.reason);
                // El enfriamiento se marca ANTES de intentar, no despues de acertar:
                // si el reinicio falla y no se marcara, un servicio roto se podria
                // reintentar en bucle, que es justo lo que el enfriamiento evita.
                lastRestartEpochMs.set(System.currentTimeMillis());
                ServiceResult restart = restartWindowsService();
                if (!restart.ok) {
                    log("ERROR", "Service restart failed: " + restart.message);
                    json(exchange, 500,
                            before.toJson(true, true, "service_restart_failed:" + escape(restart.message)));
                    return;
                }

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

    /** True while another restart must not be attempted yet. */
    static boolean cooldownActive(long lastRestartEpochMs, long nowMs, int cooldownSeconds) {
        if (lastRestartEpochMs <= 0) {
            return false;
        }
        return nowMs - lastRestartEpochMs < TimeUnit.SECONDS.toMillis(cooldownSeconds);
    }

    /** Whole seconds left in the cooldown; at least 1 while it is active. */
    static long cooldownRemainingSeconds(long lastRestartEpochMs, long nowMs, int cooldownSeconds) {
        long cooldownMs = TimeUnit.SECONDS.toMillis(cooldownSeconds);
        return Math.max(1, (cooldownMs - (nowMs - lastRestartEpochMs)) / 1000);
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
        ServiceResult stop = runFixedCommand("sc.exe", "stop", TOTALPOS_SERVICE);
        if (!stop.ok && !stop.message.toLowerCase(Locale.ROOT).contains("not started")
                && !stop.message.contains("1062")) {
            // A stopped service is acceptable; any other stop error is relevant.
            log("WARN", "Stop returned non-zero: " + stop.message);
        }

        waitForServiceState(SERVICE_STOPPED, config.serviceStopWaitSeconds);

        ServiceResult start = runFixedCommand("sc.exe", "start", TOTALPOS_SERVICE);
        if (!start.ok) {
            return start;
        }

        if (!waitForServiceState(SERVICE_RUNNING, config.serviceStartWaitSeconds)) {
            // SCM state is diagnostic only. NSSM can already have launched the
            // Bridge while sc.exe state observation is delayed or unreliable.
            // Functional readiness is decided below by /api/health, which also
            // proves that the TotalPOS SDK initialized successfully.
            BridgeHealth health = checkBridgeHealth();
            if (health.healthy) {
                log("WARN", "SCM RUNNING state was not observed, but Bridge health is OK; continuing recovery.");
                return new ServiceResult(true, "bridge_healthy_scm_state_not_observed");
            }
            log("WARN", "SCM RUNNING state was not observed; continuing with bounded functional health polling.");
            return new ServiceResult(true, "service_state_unconfirmed");
        }
        return new ServiceResult(true, "service_running");
    }

    /**
     * Wait for a Windows SCM state using its numeric state code instead of the
     * localized label emitted by sc.exe (for example STATE vs ESTADO).
     */
    private boolean waitForServiceState(int expectedStateCode, int seconds) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds);
        while (System.currentTimeMillis() < deadline) {
            ServiceResult result = runFixedCommand("sc.exe", "query", TOTALPOS_SERVICE);
            if (result.ok && extractServiceStateCode(result.message) == expectedStateCode) {
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

    /**
     * sc.exe keeps the numeric SCM state code stable across Windows languages:
     * 1=STOPPED, 2=START_PENDING, 3=STOP_PENDING, 4=RUNNING, etc.
     * Other numeric fields in sc.exe output are 0 or >=10, so the first line
     * whose value is 1..7 is the service-state line regardless of its label.
     */
    static int extractServiceStateCode(String output) {
        if (output == null || output.isBlank()) {
            return -1;
        }
        for (String line : output.split("\\R")) {
            Matcher matcher = SC_STATE_LINE.matcher(line);
            if (matcher.matches()) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        return -1;
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
        static final String DEFAULT_BRIDGE_HEALTH_URL = "http://127.0.0.1:9091/api/health";

        final String bindAddress;
        final int port;
        final String apiKey;
        final String bridgeHealthUrl;
        final int bridgeConnectTimeoutMs;
        final int bridgeReadTimeoutMs;
        final int restartCooldownSeconds;
        final int recoveryWaitSeconds;
        final int healthPollIntervalMs;
        final int commandTimeoutSeconds;
        final int serviceStopWaitSeconds;
        final int serviceStartWaitSeconds;
        final boolean startupSelfCheckEnabled;
        final int startupGraceSeconds;

        private Config(Properties p) {
            bindAddress = p.getProperty("bindAddress", "0.0.0.0").trim();
            port = Integer.parseInt(p.getProperty("port", "9092"));
            apiKey = requireSecureApiKey(p.getProperty("apiKey", ""));
            bridgeHealthUrl = requireLoopback(p.getProperty("bridgeHealthUrl", DEFAULT_BRIDGE_HEALTH_URL).trim());
            bridgeConnectTimeoutMs = Integer.parseInt(p.getProperty("bridgeConnectTimeoutMs", "1500"));
            bridgeReadTimeoutMs = Integer.parseInt(p.getProperty("bridgeReadTimeoutMs", "3000"));
            restartCooldownSeconds = Integer.parseInt(p.getProperty("restartCooldownSeconds", "120"));
            recoveryWaitSeconds = Integer.parseInt(p.getProperty("recoveryWaitSeconds", "30"));
            healthPollIntervalMs = Integer.parseInt(p.getProperty("healthPollIntervalMs", "1000"));
            commandTimeoutSeconds = Integer.parseInt(p.getProperty("commandTimeoutSeconds", "10"));
            serviceStopWaitSeconds = Integer.parseInt(p.getProperty("serviceStopWaitSeconds", "10"));
            serviceStartWaitSeconds = Integer.parseInt(p.getProperty("serviceStartWaitSeconds", "15"));
            startupSelfCheckEnabled = Boolean.parseBoolean(p.getProperty("startupSelfCheckEnabled", "true"));
            startupGraceSeconds = Integer.parseInt(p.getProperty("startupGraceSeconds", "60"));
            if (startupGraceSeconds < 10) {
                throw new IllegalArgumentException("startupGraceSeconds must be at least 10");
            }
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

        /**
         * El bridge corre en la misma maquina. Aceptar un host remoto convertiria
         * este servicio en un sondeador de equipos ajenos.
         */
        private static String requireLoopback(String value) {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host == null || !(host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1"))) {
                throw new IllegalArgumentException(
                        "bridgeHealthUrl must point to this machine (127.0.0.1 or localhost)");
            }
            return value;
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
