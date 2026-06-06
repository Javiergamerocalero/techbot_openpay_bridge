package com.notariapaino.bridge.routes;

import com.eglobal.totalpos.sdk.authorizer.Peticion;
import com.eglobal.totalpos.sdk.catalog.OPERACION;
import com.eglobal.totalpos.sdk.exception.PeticionException;
import com.eglobal.totalpos.sdk.layout.Respuesta;
import com.notariapaino.bridge.SdkManager;
import com.notariapaino.bridge.dto.ApiError;
import com.notariapaino.bridge.dto.RespuestaDto;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Manual key-load endpoint: {@code POST /api/carga-llaves}.
 *
 * <p>Per SDK manual page 44-45, key loading has three modes:
 * <ol>
 *   <li>Manual — what this endpoint exposes. The operator triggers it
 *       (typically once at PinPad provisioning, or when prompted by a
 *       {@code PinPadKeysException} at venta time).</li>
 *   <li>At initialization — the SDK auto-runs it during
 *       {@code Interfaz.inicializar()} when the host flags
 *       {@code cargaLlavesAutomaticaRequerida=true}. Out of our control.</li>
 *   <li>Automatic mid-flow — when the host signals after a venta that
 *       new keys must be loaded; the SDK handles this transparently and
 *       reports the result on the PinPad screen. Also out of our control.</li>
 * </ol>
 *
 * <p>This endpoint only triggers mode (1). It's the "fix it" button the
 * operator presses when a sale fails with "Requiere carga de llaves".
 */
public final class CargaLlavesController {

    private static final Logger log = LoggerFactory.getLogger(CargaLlavesController.class);
    private static final ThreadLocal<SimpleDateFormat> DATE_FMT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyyMMddHHmmss"));

    private final SdkManager sdk;

    public CargaLlavesController(SdkManager sdk) {
        this.sdk = sdk;
    }

    public Handler cargaLlaves() {
        return new Handler() {
            @Override
            public void handle(@NotNull Context ctx) {
                try {
                    Peticion p = new Peticion();
                    p.setOperador(sdk.operador());
                    p.setFecha(DATE_FMT.get().format(new Date()));
                    p.setOperacion(OPERACION.CARGA_LLAVES, null);

                    Respuesta resp = p.autorizar();
                    log.info("CARGA_LLAVES codigoRespuesta={} leyenda={}",
                            resp.getCodigoRespuesta(), resp.getLeyenda());
                    ctx.status(200).json(RespuestaDto.from(resp));

                } catch (PeticionException e) {
                    log.error("CARGA_LLAVES failed: {}", e.getMessage());
                    ctx.status(502).json(ApiError.of("PeticionException", e.getMessage()));
                } catch (Exception e) {
                    log.error("CARGA_LLAVES unexpected error", e);
                    ctx.status(500).json(ApiError.of(
                            e.getClass().getSimpleName(),
                            e.getMessage() == null ? "Error inesperado" : e.getMessage()));
                }
            }
        };
    }
}
