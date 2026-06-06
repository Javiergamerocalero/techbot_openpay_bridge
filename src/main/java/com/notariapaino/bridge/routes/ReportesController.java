package com.notariapaino.bridge.routes;

import com.eglobal.totalpos.sdk.authorizer.Reporteria;
import com.eglobal.totalpos.sdk.catalog.MONEDA;
import com.eglobal.totalpos.sdk.exception.PeticionException;
import com.notariapaino.bridge.db.TransactionDao;
import com.notariapaino.bridge.dto.ApiError;
import com.notariapaino.bridge.report.ReportAggregator;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reportería endpoints.
 *
 * <p><b>Detallado / Totalizado:</b> built from the bridge's local SQLite cache
 * ({@link TransactionDao}). The SDK's in-memory {@code Reporteria} would also
 * work, but its state is volatile — any service restart (NSSM update, Windows
 * reboot, crash + auto-restart) wipes the shift's report even though BBVA's
 * host still has the transactions, and the kiosk would show an empty report
 * mid-shift. The SQLite cache survives restarts.
 *
 * <p>The shape we return matches what the Flutter
 * {@code TotalPosReporteDetallado.fromJson} / {@code TotalPosReporteTotalizado
 * .fromJson} parse, so no client change is needed:
 *
 * <pre>{@code
 * GET /api/reportes/totalizado?moneda=SOLES
 *   → {
 *       "totalSoles": 300.92,
 *       "totalDolares": 0.0,
 *       "cantidadVentas": 3,
 *       "cantidadAnulaciones": 0,
 *       "anuladoSoles": 0.0,
 *       "anuladoDolares": 0.0,
 *       "netoSoles": 300.92,
 *       "netoDolares": 0.0,
 *       "idTurno": ""
 *     }
 *
 * GET /api/reportes/detallado?moneda=SOLES
 *   → {
 *       "transactions": [ {idTransaccion, tipo, importe, ...}, ... ],
 *       "totales":      { ... same shape as totalizado ... }
 *     }
 * }</pre>
 *
 * <p>The {@code moneda} query param is accepted for backward compatibility
 * with the client, but the report always includes BOTH currencies in its
 * totals — Flutter shows soles and dollars side by side.
 *
 * <p><b>Firma / Cierre operaciones / Cierre resumen:</b> these still go through
 * the SDK's {@code Reporteria} because they're meant to query BBVA's host for
 * historical / audit data, not the live shift cache.
 */
public final class ReportesController {

    private static final Logger log = LoggerFactory.getLogger(ReportesController.class);

    private final TransactionDao dao;

    public ReportesController(TransactionDao dao) {
        this.dao = dao;
    }

    // ─── Live-shift reports (SQLite-backed) ─────────────────

    public Handler totalizado() {
        return new Handler() {
            @Override
            public void handle(@NotNull Context ctx) {
                List<Map<String, Object>> rows = dao.listCurrentShift();
                Map<String, Object> totals = ReportAggregator.aggregateTotals(rows);
                log.info("REPORTE_TOTALIZADO (SQLite) rows={} ventas={} anulaciones={}",
                        rows.size(), totals.get("cantidadVentas"),
                        totals.get("cantidadAnulaciones"));
                ctx.status(200).json(totals);
            }
        };
    }

    public Handler detallado() {
        return new Handler() {
            @Override
            public void handle(@NotNull Context ctx) {
                List<Map<String, Object>> rows = dao.listCurrentShift();
                Map<String, Object> totals = ReportAggregator.aggregateTotals(rows);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("transactions", rows);
                body.put("totales", totals);
                log.info("REPORTE_DETALLADO (SQLite) rows={}", rows.size());
                ctx.status(200).json(body);
            }
        };
    }

    // ─── SDK-backed reports (audit / historical) ────────────

    public Handler firma() {
        return monedaHandler(
                "REPORTE_FIRMA",
                moneda -> Reporteria.generarReporteFirma(moneda)
        );
    }

    public Handler cierreOperaciones() {
        // SDK 1.1.13 has two methods:
        //   - generarReporteCierreOperaciones(fechaInicial, fechaFinal) for ranges
        //   - reimprimirReporteCierreOperaciones(idTurno) for a specific
        //     already-closed shift.
        // The manual page 47 documents only the 1-arg form, which now maps
        // to `reimprimirReporteCierreOperaciones`. That's the semantic the
        // kiosk app needs: re-fetch a specific turno's detail by id.
        return turnoHandler(
                "REPORTE_CIERRE_OPERACIONES",
                idTurno -> Reporteria.reimprimirReporteCierreOperaciones(idTurno)
        );
    }

    public Handler cierreResumen() {
        return turnoHandler(
                "REPORTE_CIERRE_RESUMEN",
                idTurno -> Reporteria.reimprimirReporteCierreResumen(idTurno)
        );
    }

    // ─── SDK report helpers (firma / cierre) ────────────────

    /** Common implementation for the {@code ?moneda=…}-keyed SDK reports. */
    private Handler monedaHandler(String tag, MonedaReport call) {
        return new Handler() {
            @Override
            public void handle(@NotNull Context ctx) {
                String monedaParam = ctx.queryParam("moneda");
                if (monedaParam == null || monedaParam.isBlank()) {
                    ctx.status(400).json(ApiError.badRequest("Falta query param 'moneda' (SOLES o DOLARES)"));
                    return;
                }
                MONEDA moneda;
                try {
                    moneda = MONEDA.valueOf(monedaParam.toUpperCase());
                } catch (IllegalArgumentException e) {
                    ctx.status(400).json(ApiError.badRequest(
                            "moneda inválida: " + monedaParam + " (use SOLES o DOLARES)"));
                    return;
                }
                try {
                    Object resp = call.run(moneda);
                    log.info("{} moneda={} OK", tag, moneda);
                    ctx.status(200).json(resp);
                } catch (PeticionException e) {
                    log.error("{} moneda={} failed: {}", tag, moneda, e.getMessage());
                    ctx.status(502).json(ApiError.of("PeticionException", e.getMessage()));
                } catch (Exception e) {
                    log.error("{} unexpected error", tag, e);
                    ctx.status(500).json(ApiError.of(
                            e.getClass().getSimpleName(),
                            e.getMessage() == null ? "Error inesperado" : e.getMessage()));
                }
            }
        };
    }

    /** Common implementation for the two {@code ?idTurno=…}-keyed SDK reports. */
    private Handler turnoHandler(String tag, TurnoReport call) {
        return new Handler() {
            @Override
            public void handle(@NotNull Context ctx) {
                String idTurno = ctx.queryParam("idTurno");
                if (idTurno == null || idTurno.isBlank()) {
                    ctx.status(400).json(ApiError.badRequest(
                            "Falta query param 'idTurno' (alfanumérico, 1-4 caracteres)"));
                    return;
                }
                try {
                    Object resp = call.run(idTurno);
                    log.info("{} idTurno={} OK", tag, idTurno);
                    ctx.status(200).json(resp);
                } catch (PeticionException e) {
                    log.error("{} idTurno={} failed: {}", tag, idTurno, e.getMessage());
                    ctx.status(502).json(ApiError.of("PeticionException", e.getMessage()));
                } catch (Exception e) {
                    log.error("{} unexpected error", tag, e);
                    ctx.status(500).json(ApiError.of(
                            e.getClass().getSimpleName(),
                            e.getMessage() == null ? "Error inesperado" : e.getMessage()));
                }
            }
        };
    }

    @FunctionalInterface
    private interface MonedaReport {
        Object run(MONEDA moneda) throws Exception;
    }

    @FunctionalInterface
    private interface TurnoReport {
        Object run(String idTurno) throws Exception;
    }
}
