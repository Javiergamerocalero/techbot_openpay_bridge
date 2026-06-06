package com.notariapaino.bridge.routes;

import com.eglobal.totalpos.sdk.authorizer.Peticion;
import com.eglobal.totalpos.sdk.exception.PeticionException;
import com.eglobal.totalpos.sdk.layout.Respuesta;
import com.notariapaino.bridge.dto.ApiError;
import com.notariapaino.bridge.dto.RespuestaDto;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lookup endpoint: {@code GET /api/consultar/{idTransaccion}}.
 *
 * <p>Per SDK manual page 36, used to recover the state of a transaction
 * when the SDK returns {@code codigoRespuesta == "99"} ("estado indeterminado,
 * consultar estado") — typically after a QR session times out without
 * confirmation. The kiosk MUST call this before retrying or the operator
 * risks a double charge.
 *
 * <p>The SDK's {@code Peticion.consultarTransaccion(id)} populates the
 * peticion with the consulta request, then {@code autorizar()} fires it
 * against the host's consulta endpoint and returns the latest known
 * Respuesta for that transaction.
 */
public final class ConsultaController {

    private static final Logger log = LoggerFactory.getLogger(ConsultaController.class);

    public Handler consultar() {
        return new Handler() {
            @Override
            public void handle(@NotNull Context ctx) {
                String idTx = ctx.pathParam("idTransaccion");
                if (idTx == null || idTx.isBlank()) {
                    ctx.status(400).json(ApiError.badRequest("Falta path param idTransaccion"));
                    return;
                }
                try {
                    Peticion p = new Peticion();
                    p.consultarTransaccion(idTx);

                    Respuesta resp = p.autorizar();
                    log.info("CONSULTA id={} codigoRespuesta={} leyenda={}",
                            idTx, resp.getCodigoRespuesta(), resp.getLeyenda());
                    ctx.status(200).json(RespuestaDto.from(resp));

                } catch (PeticionException e) {
                    log.error("CONSULTA failed id={}: {}", idTx, e.getMessage());
                    ctx.status(502).json(ApiError.of("PeticionException", e.getMessage()));
                } catch (Exception e) {
                    log.error("CONSULTA unexpected error id={}", idTx, e);
                    ctx.status(500).json(ApiError.of(
                            e.getClass().getSimpleName(),
                            e.getMessage() == null ? "Error inesperado" : e.getMessage()));
                }
            }
        };
    }
}
