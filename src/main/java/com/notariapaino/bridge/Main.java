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

/** Entry point of the TotalPOS Bridge. */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final String VERSION = "1.2.0";

    public static void main(String[] args) {
        log.info("════════════════════════════════════════════════════════");
        log.info("  TotalPOS Bridge v{} starting…", VERSION);
        log.info("════════════════════════════════════════════════════════");

        AppConfig cfg = AppConfig.load();

        // Phase 0: preserve the later self-heal protection that was added to
        // source after the production 1.1.9 JAR was built.
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

        SdkManager sdk = new SdkManager(cfg);
        try {
            SelfHeal.retry("init BBVA SDK", 2, 3000L, () -> { sdk.init(); return null; });
        } catch (Exception e) {
            throw new RuntimeException("SDK init failed after retries: " + e.getMessage(), e);
        }

        HealthController health = new HealthController(sdk, VERSION);
        VentaController venta = new VentaController(sdk, dao);
        AnulacionController anulacion = new AnulacionController(sdk, dao);
        CierreController cierre = new CierreController(sdk, dao);
        CargaLlavesController cargaLlaves = new CargaLlavesController(sdk);
        ConsultaController consulta = new ConsultaController();
        ReportesController reportes = new ReportesController(dao);
        TransactionsController transactions = new TransactionsController(dao);
        VoucherController voucher = new VoucherController(dao, sdk);
        LogsController logs = new LogsController(cfg);

        Javalin app = Javalin.create(config -> config.showJavalinBanner = false);

        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled exception in route {} {}: {}",
                    ctx.method(), ctx.path(), e.getMessage(), e);
            ctx.status(500).json(ApiError.of(
                    e.getClass().getSimpleName(),
                    e.getMessage() == null ? "Internal error" : e.getMessage()));
        });

        app.get("/api/health", health.get());

        app.post("/api/venta", venta.ventaTarjeta());
        app.post("/api/venta-qr", venta.ventaQR());
        app.post("/api/venta-qr/cancelar", venta.cancelarQR());

        app.post("/api/anulacion-tarjeta", anulacion.anulacionTarjeta());
        app.post("/api/anulacion-qr", anulacion.anulacionQR());

        app.post("/api/cierre-turno", cierre.cierreTurno());
        app.post("/api/carga-llaves", cargaLlaves.cargaLlaves());

        app.get("/api/consultar/{idTransaccion}", consulta.consultar());

        app.get("/api/transactions/turno-actual", transactions.currentShift());
        app.get("/api/transactions/{id}/voucher", voucher.voucher());

        app.get("/api/reportes/firma", reportes.firma());
        app.get("/api/reportes/totalizado", reportes.totalizado());
        app.get("/api/reportes/detallado", reportes.detallado());
        app.get("/api/reportes/cierre/operaciones", reportes.cierreOperaciones());
        app.get("/api/reportes/cierre/resumen", reportes.cierreResumen());

        app.get("/api/logs/files", logs.list());
        app.get("/api/logs/{filename}", logs.download());

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
