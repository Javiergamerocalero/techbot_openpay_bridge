package com.notariapaino.bridge;

import com.notariapaino.bridge.config.AppConfig;
import com.notariapaino.bridge.db.TransactionDao;
import com.notariapaino.bridge.dto.ApiError;
import com.notariapaino.bridge.routes.AnulacionController;
import com.notariapaino.bridge.routes.CargaLlavesController;
import com.notariapaino.bridge.routes.CierreController;
import com.notariapaino.bridge.routes.ConsultaController;
import com.notariapaino.bridge.routes.HealthController;
import com.notariapaino.bridge.routes.LogsController;
import com.notariapaino.bridge.routes.ReportesController;
import com.notariapaino.bridge.routes.TransactionsController;
import com.notariapaino.bridge.routes.VentaController;
import com.notariapaino.bridge.routes.VoucherController;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the TotalPOS Bridge.
 *
 * <p>Startup sequence:
 * <ol>
 *   <li>Load {@code application.yaml}.</li>
 *   <li>Initialize the SQLite persistence layer (schema migration).</li>
 *   <li>Initialize the TotalPOS SDK (config + {@code Interfaz.inicializar()}).
 *       This boots the reverso scheduler, downloads bines / configuracion,
 *       and syncs the PinPad.</li>
 *   <li>Build the Javalin app and register every route's controller.</li>
 *   <li>Bind to the configured host:port and serve forever.</li>
 *   <li>Install a JVM shutdown hook to stop Javalin gracefully on SIGTERM
 *       so NSSM-managed restarts are clean.</li>
 * </ol>
 *
 * <p>Any exception during steps 1-3 propagates and causes the process to
 * exit non-zero. NSSM will restart us — we'd rather restart loop than serve
 * 500s from a half-initialized state.
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final String VERSION = "1.1.4";

    public static void main(String[] args) {
        log.info("════════════════════════════════════════════════════════");
        log.info("  TotalPOS Bridge v{} starting…", VERSION);
        log.info("════════════════════════════════════════════════════════");

        AppConfig cfg = AppConfig.load();

        // ─── Phase 0: self-heal any stale state from a previous abrupt
        // shutdown (power loss, forced reboot). Runs BEFORE opening the DB
        // and BEFORE talking to the SDK, so that a corrupt SQLite journal
        // or a stale lock file doesn't send the whole boot into an NSSM
        // restart loop. See {@link SelfHeal}.
        SelfHeal.healSqlite(cfg.persistence.dbFile);

        TransactionDao dao;
        try {
            dao = SelfHeal.retry("open SQLite + init schema", 3, 2000L, () -> {
                TransactionDao d = new TransactionDao(cfg.persistence.dbFile);
                d.initSchema();
                return d;
            });
        } catch (Exception e) {
            throw new RuntimeException("SQLite init failed after retries: " + e.getMessage(), e);
        }

        // SDK init can transiently fail right after boot because the USB
        // stack is still enumerating COM ports for the PinPad, or because
        // the BBVA host TLS handshake races with the network coming up.
        // Two attempts with a 3s backoff catches those cases without
        // masking real config errors (which fail deterministically on both).
        SdkManager sdk = new SdkManager(cfg);
        try {
            SelfHeal.retry("init BBVA SDK", 2, 3000L, () -> { sdk.init(); return null; });
        } catch (Exception e) {
            throw new RuntimeException("SDK init failed after retries: " + e.getMessage(), e);
        }

        // ─── Controllers ──────────────────────────────────
        HealthController health = new HealthController(sdk, VERSION);
        VentaController venta = new VentaController(sdk, dao);
        AnulacionController anulacion = new AnulacionController(sdk, dao);
        CierreController cierre = new CierreController(sdk, dao);
        CargaLlavesController cargaLlaves = new CargaLlavesController(sdk);
        ConsultaController consulta = new ConsultaController();
        // Reportes lee del cache SQLite (no del estado en memoria del SDK)
        // para que el reporte sobreviva reinicios del servicio.
        ReportesController reportes = new ReportesController(dao);
        TransactionsController transactions = new TransactionsController(dao);
        VoucherController voucher = new VoucherController(dao, sdk);
        LogsController logs = new LogsController(cfg);

        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
        });

        // Global exception handler — convert anything unexpected into a
        // JSON ApiError so the kiosk client always receives a parseable body.
        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled exception in route {} {}: {}",
                    ctx.method(), ctx.path(), e.getMessage(), e);
            ctx.status(500).json(ApiError.of(
                    e.getClass().getSimpleName(),
                    e.getMessage() == null ? "Internal error" : e.getMessage()));
        });

        // ─── Routes ────────────────────────────────────────

        // Health / readiness
        app.get("/api/health", health.get());

        // Sales
        app.post("/api/venta", venta.ventaTarjeta());
        app.post("/api/venta-qr", venta.ventaQR());
        app.post("/api/venta-qr/cancelar", venta.cancelarQR());

        // Voids
        app.post("/api/anulacion-tarjeta", anulacion.anulacionTarjeta());
        app.post("/api/anulacion-qr", anulacion.anulacionQR());

        // Shift close / key load
        app.post("/api/cierre-turno", cierre.cierreTurno());
        app.post("/api/carga-llaves", cargaLlaves.cargaLlaves());

        // Transaction lookup
        app.get("/api/consultar/{idTransaccion}", consulta.consultar());

        // Local persistence read
        app.get("/api/transactions/turno-actual", transactions.currentShift());
        app.get("/api/transactions/{id}/voucher", voucher.voucher());

        // Reportería
        app.get("/api/reportes/firma", reportes.firma());
        app.get("/api/reportes/totalizado", reportes.totalizado());
        app.get("/api/reportes/detallado", reportes.detallado());
        app.get("/api/reportes/cierre/operaciones", reportes.cierreOperaciones());
        app.get("/api/reportes/cierre/resumen", reportes.cierreResumen());

        // SDK log files
        app.get("/api/logs/files", logs.list());
        app.get("/api/logs/{filename}", logs.download());

        // ─── Bind & serve ──────────────────────────────────
        app.start(cfg.server.host, cfg.server.port);
        log.info("Bridge listening on http://{}:{}", cfg.server.host, cfg.server.port);
        log.info("Try: curl http://{}:{}/api/health",
                "0.0.0.0".equals(cfg.server.host) ? "localhost" : cfg.server.host,
                cfg.server.port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown hook triggered — stopping Javalin");
            app.stop();
        }, "bridge-shutdown"));
    }
}
