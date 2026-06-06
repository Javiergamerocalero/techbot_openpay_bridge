package com.notariapaino.bridge.routes;

import com.eglobal.totalpos.sdk.authorizer.Peticion;
import com.eglobal.totalpos.sdk.catalog.OPERACION;
import com.eglobal.totalpos.sdk.exception.PeticionException;
import com.eglobal.totalpos.sdk.layout.Respuesta;
import com.notariapaino.bridge.SdkManager;
import com.notariapaino.bridge.db.TransactionDao;
import com.notariapaino.bridge.dto.ApiError;
import com.notariapaino.bridge.report.ReportAggregator;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Close-shift endpoint: {@code POST /api/cierre-turno}.
 *
 * <p>Triggers {@code OPERACION.CIERRE_TURNO} on the SDK, which asks the host
 * to close the current turno. On a successful close
 * ({@code codigoRespuesta == "00"}) we drop every transaction the bridge
 * had cached locally for this shift, so the next shift starts clean.
 *
 * <p>The SDK's own response for CIERRE_TURNO only carries {@code idTurno},
 * {@code autorizacion}, and {@code leyenda} — it does NOT include the
 * shift's totals. So before triggering the close, we snapshot the cached
 * transactions and aggregate them via {@link ReportAggregator}. Those
 * totals are then merged into the response payload so the kiosk's
 * "Cierre Exitoso" screen can show the per-shift numbers the operator
 * expects (cantidadVentas, cantidadAnulaciones, totalSoles, netoSoles, …).
 *
 * <p>The aggregation runs BEFORE {@code clearShift()} so the snapshot
 * captures the just-closed shift. After {@code clearShift()} runs, the
 * cache is empty and there's nothing left to count.
 *
 * <p>No request body required (per SDK manual page 42, cierre is parameterless).
 */
public final class CierreController {

    private static final Logger log = LoggerFactory.getLogger(CierreController.class);
    private static final ThreadLocal<SimpleDateFormat> DATE_FMT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyyMMddHHmmss"));

    private final SdkManager sdk;
    private final TransactionDao dao;

    public CierreController(SdkManager sdk, TransactionDao dao) {
        this.sdk = sdk;
        this.dao = dao;
    }

    public Handler cierreTurno() {
        return new Handler() {
            @Override
            public void handle(@NotNull Context ctx) {
                // Snapshot pre-close so we have totals to return to the kiosk.
                List<Map<String, Object>> rowsAtCloseTime = dao.listCurrentShift();
                Map<String, Object> totalsSnapshot =
                        ReportAggregator.aggregateTotals(rowsAtCloseTime);

                try {
                    Peticion p = new Peticion();
                    p.setOperador(sdk.operador());
                    p.setFecha(DATE_FMT.get().format(new Date()));
                    p.setOperacion(OPERACION.CIERRE_TURNO, null);

                    Respuesta resp = p.autorizar();
                    log.info("CIERRE_TURNO codigoRespuesta={} leyenda={} idTurno={} rowsAggregated={}",
                            resp.getCodigoRespuesta(), resp.getLeyenda(),
                            resp.getIdTurno(), rowsAtCloseTime.size());

                    if ("00".equals(resp.getCodigoRespuesta())) {
                        dao.clearShift();
                        // Inject the SDK's idTurno into the totals so the
                        // kiosk has it on one place. The SDK already provides
                        // it at top-level (resp.idTurno), this is just a
                        // belt-and-braces convenience for the totales block.
                        if (resp.getIdTurno() != null) {
                            totalsSnapshot.put("idTurno", resp.getIdTurno());
                        }
                    }

                    // Build the response: SDK Respuesta + the totals snapshot
                    // merged in. We use the existing RespuestaDto and inject
                    // a "totales" key into its data map.
                    Map<String, Object> respData = new LinkedHashMap<>(resp);
                    respData.put("totales", totalsSnapshot);

                    String code = String.valueOf(respData.getOrDefault("codigoRespuesta", ""));
                    String legend = String.valueOf(respData.getOrDefault("leyenda", ""));
                    boolean ok = "00".equals(code) || "D1".equals(code);

                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("success", ok);
                    body.put("codigoRespuesta", code);
                    body.put("leyenda", legend);
                    body.put("data", respData);

                    ctx.status(200).json(body);

                } catch (PeticionException e) {
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    log.error("CIERRE_TURNO failed: {}", msg);
                    // BBVA's `/cierre-turno/turno-actual` returns 204 No Content
                    // when there's no open shift to close (typically because no
                    // sales have happened yet today). The SDK surfaces this as
                    // "No puedes hacer el cierre". Convert it into something
                    // a cashier can act on instead of a generic 502.
                    if (msg.toLowerCase().contains("no puedes hacer el cierre")
                            || msg.toLowerCase().contains("no hay turno")) {
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("success", false);
                        body.put("codigoRespuesta", "NO_SHIFT");
                        body.put("leyenda", "No hay turno abierto para cerrar");
                        body.put("error",
                                "No hay un turno abierto en BBVA. Esto sucede "
                                + "cuando aún no se ha hecho ninguna venta del "
                                + "día (el turno se abre al procesar la primera "
                                + "venta). Realice una venta y vuelva a intentar "
                                + "el cierre.");
                        ctx.status(409).json(body);
                        return;
                    }
                    ctx.status(502).json(ApiError.of("PeticionException", msg));
                } catch (Exception e) {
                    log.error("CIERRE_TURNO unexpected error", e);
                    ctx.status(500).json(ApiError.of(
                            e.getClass().getSimpleName(),
                            e.getMessage() == null ? "Error inesperado" : e.getMessage()));
                }
            }
        };
    }
}
