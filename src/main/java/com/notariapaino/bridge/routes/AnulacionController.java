package com.notariapaino.bridge.routes;

import com.eglobal.totalpos.sdk.authorizer.Peticion;
import com.eglobal.totalpos.sdk.catalog.OPERACION;
import com.eglobal.totalpos.sdk.catalog.PARAMETRO_OPERACION;
import com.eglobal.totalpos.sdk.exception.PeticionException;
import com.eglobal.totalpos.sdk.exception.PinPadKeysException;
import com.eglobal.totalpos.sdk.layout.Respuesta;
import com.notariapaino.bridge.SdkManager;
import com.notariapaino.bridge.db.TransactionDao;
import com.notariapaino.bridge.dto.AnulacionRequest;
import com.notariapaino.bridge.dto.ApiError;
import com.notariapaino.bridge.dto.RespuestaDto;
import com.notariapaino.bridge.voucher.VoucherFormatter;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Void endpoints: card sale ({@code POST /api/anulacion-tarjeta}) and QR sale
 * ({@code POST /api/anulacion-qr}).
 *
 * <p>Both require the original sale's {@code importe} and
 * {@code referenciaFinanciera} (12-char alphanumeric, validated server-side
 * by the SDK against {@code ^[A-Za-z0-9]{12}$}).
 *
 * <p>Important contract differences from sales:
 * <ul>
 *   <li>Card anulación needs the cardholder physically present to re-tap
 *       the same card on the PinPad (the SDK reads the card during
 *       {@code preAutorizar}). QR anulación is a pure host call, no card.</li>
 *   <li>For QR anulación, the SDK treats BOTH {@code codigoRespuesta == "00"}
 *       AND {@code "D1"} as approved (per the SDK's
 *       {@code AnulacionVentaQr.autorizar} bytecode). {@link RespuestaDto#success}
 *       already accounts for this.</li>
 * </ul>
 */
public final class AnulacionController {

    private static final Logger log = LoggerFactory.getLogger(AnulacionController.class);
    private static final ThreadLocal<SimpleDateFormat> DATE_FMT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyyMMddHHmmss"));

    private final SdkManager sdk;
    private final TransactionDao dao;

    public AnulacionController(SdkManager sdk, TransactionDao dao) {
        this.sdk = sdk;
        this.dao = dao;
    }

    public Handler anulacionTarjeta() {
        return run(OPERACION.ANULACION_VENTA_TARJETA, "ANULACION_VENTA_TARJETA");
    }

    public Handler anulacionQR() {
        return run(OPERACION.ANULACION_VENTA_QR, "ANULACION_VENTA_QR");
    }

    private Handler run(OPERACION op, String tag) {
        return new Handler() {
            @Override
            public void handle(@NotNull Context ctx) {
                AnulacionRequest req = ctx.bodyAsClass(AnulacionRequest.class);
                if (req.importe() == null || req.importe().isBlank()) {
                    ctx.status(400).json(ApiError.badRequest("Falta 'importe'"));
                    return;
                }
                if (req.referenciaFinanciera() == null
                        || !req.referenciaFinanciera().matches("^[A-Za-z0-9]{12}$")) {
                    ctx.status(400).json(ApiError.badRequest(
                            "'referenciaFinanciera' debe ser 12 caracteres alfanuméricos"));
                    return;
                }

                Map<PARAMETRO_OPERACION, Object> parametros = new HashMap<>();
                parametros.put(PARAMETRO_OPERACION.IMPORTE, req.importe());
                parametros.put(PARAMETRO_OPERACION.REFERENCIA_FINANCIERA, req.referenciaFinanciera());

                try {
                    Peticion p = new Peticion();
                    p.setOperador(sdk.operador());
                    p.setFecha(DATE_FMT.get().format(new Date()));
                    p.setOperacion(op, parametros);

                    Respuesta resp = p.autorizar();
                    log.info("{} codigoRespuesta={} leyenda={} ref={}",
                            tag, resp.getCodigoRespuesta(), resp.getLeyenda(),
                            req.referenciaFinanciera());

                    // For QR voids the SDK accepts "00" OR "D1" as approved.
                    String code = resp.getCodigoRespuesta();
                    boolean approved = "00".equals(code) || "D1".equals(code);
                    String voucherText = null;
                    if (approved) {
                        dao.markAnuladaByReferencia(req.referenciaFinanciera());
                        dao.persist(tag, resp);
                        // Build the printable voucher inline so the kiosk
                        // can hand it to the MW printer with no extra call.
                        Map<String, Object> tx = dao.findByIdTransaccion(resp.getIdTransaccion());
                        if (tx != null) {
                            voucherText = VoucherFormatter.build(tx, sdk.operador(), null);
                        }
                    }
                    ctx.status(200).json(RespuestaDto.from(resp, voucherText));

                } catch (PinPadKeysException e) {
                    // Only ANULACION_VENTA_TARJETA needs keys (it reads the card).
                    // For QR void this branch is unreachable, but harmless.
                    log.warn("{} blocked — PinPad requires key loading", tag);
                    ctx.status(409).json(ApiError.requiresKeyLoad());
                } catch (PeticionException e) {
                    log.error("{} failed: {}", tag, e.getMessage());
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
}
