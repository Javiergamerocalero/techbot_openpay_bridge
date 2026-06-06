package com.notariapaino.bridge.routes;

import com.eglobal.totalpos.sdk.authorizer.Peticion;
import com.eglobal.totalpos.sdk.catalog.OPERACION;
import com.eglobal.totalpos.sdk.catalog.PARAMETRO_OPERACION;
import com.eglobal.totalpos.sdk.catalog.PROMOCION;
import com.eglobal.totalpos.sdk.exception.PeticionException;
import com.eglobal.totalpos.sdk.exception.PinPadKeysException;
import com.eglobal.totalpos.sdk.layout.Respuesta;
import com.eglobal.totalpos.sdk.layout.Tarjeta;
import com.notariapaino.bridge.SdkManager;
import com.notariapaino.bridge.db.TransactionDao;
import com.notariapaino.bridge.dto.ApiError;
import com.notariapaino.bridge.dto.RespuestaDto;
import com.notariapaino.bridge.dto.VentaRequest;
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
 * Sale endpoints: card sale ({@code POST /api/venta}) and QR sale
 * ({@code POST /api/venta-qr}).
 *
 * <p>Both call the SDK's {@code Peticion.autorizar()} with the appropriate
 * {@code OPERACION} enum and surface the SDK's {@code Respuesta} as a
 * {@link RespuestaDto}. Errors map to {@link ApiError}:
 *
 * <ul>
 *   <li>400 — bad request body (importe missing / malformed).</li>
 *   <li>409 — {@code PinPadKeysException}: PinPad needs key loading first.</li>
 *   <li>502 — {@code PeticionException}: host/PinPad communication failure.</li>
 *   <li>500 — any other unexpected error.</li>
 * </ul>
 *
 * <p>The QR endpoint adds a companion cancel route
 * ({@code POST /api/venta-qr/cancelar}) that the kiosk can call when the
 * operator hits "Cancelar" on the UI before the customer scans the QR.
 * Maps to {@code Peticion.finalizarOperacionQR()} per SDK manual page 31.
 */
public final class VentaController {

    private static final Logger log = LoggerFactory.getLogger(VentaController.class);
    private static final ThreadLocal<SimpleDateFormat> DATE_FMT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyyMMddHHmmss"));

    private final SdkManager sdk;
    private final TransactionDao dao;

    /**
     * Tracks the currently-in-flight QR Peticion so {@code /cancelar} can
     * call {@code finalizarOperacionQR()} on the right instance. Only one
     * QR can be in flight at a time on a single PinPad.
     */
    private volatile Peticion currentQrPeticion;

    public VentaController(SdkManager sdk, TransactionDao dao) {
        this.sdk = sdk;
        this.dao = dao;
    }

    public Handler ventaTarjeta() {
        return new Handler() {
            @Override
            public void handle(@NotNull Context ctx) {
                VentaRequest req = ctx.bodyAsClass(VentaRequest.class);
                if (req.importe() == null || req.importe().isBlank()) {
                    ctx.status(400).json(ApiError.badRequest("Falta 'importe'"));
                    return;
                }

                Map<PARAMETRO_OPERACION, Object> parametros = new HashMap<>();
                parametros.put(PARAMETRO_OPERACION.IMPORTE, req.importe());
                if (req.hasTip()) {
                    parametros.put(PARAMETRO_OPERACION.PROPINA, req.propina());
                }

                try {
                    // `new Peticion()` itself declares throws PeticionException —
                    // keep inside the try so we never have an unreported
                    // exception path.
                    Peticion p = new Peticion();
                    p.setOperador(sdk.operador());
                    p.setFecha(DATE_FMT.get().format(new Date()));
                    p.setOperacion(OPERACION.VENTA, parametros);

                    // Cuotas / PSI flow: the SDK manual (pages 32-33) requires
                    // reading the card first to know whether the promo applies,
                    // then calling setPromocionMeses before autorizar.
                    if (req.hasInstallments()) {
                        Tarjeta tarjeta = p.leerTarjeta();
                        PROMOCION promo = req.isSinIntereses()
                                ? PROMOCION.MESES_SIN_INTERESES
                                : PROMOCION.MESES_CON_INTERESES;
                        // For PSI, only offer if the host indicated it via getMaxCuotasPsi() > 0.
                        // For regular installments, host signals via isCuotasPci(). We
                        // trust the caller (Flutter UI) to have already filtered; if the
                        // card doesn't support the promo, the host will reject downstream.
                        p.setPromocionMeses(promo, req.cuotas());
                        log.info("VENTA installments: cuotas={}, promo={}, productoTarjeta={}",
                                req.cuotas(), promo, tarjeta == null ? "?" : tarjeta.getProducto());
                    }

                    Respuesta resp = p.autorizar();
                    log.info("VENTA codigoRespuesta={} leyenda={} importe={}",
                            resp.getCodigoRespuesta(), resp.getLeyenda(), req.importe());
                    String voucherText = null;
                    if ("00".equals(resp.getCodigoRespuesta())) {
                        dao.persist("VENTA", resp);
                        // Build the printable voucher inline so the kiosk gets
                        // it in the same response (no extra round-trip). The
                        // MW's printer service is what actually prints it.
                        Map<String, Object> tx = dao.findByIdTransaccion(resp.getIdTransaccion());
                        if (tx != null) {
                            voucherText = VoucherFormatter.build(tx, sdk.operador(), null);
                        }
                    }
                    ctx.status(200).json(RespuestaDto.from(resp, voucherText));

                } catch (PinPadKeysException e) {
                    log.warn("VENTA blocked — PinPad requires key loading: {}", e.getMessage());
                    ctx.status(409).json(ApiError.requiresKeyLoad());
                } catch (PeticionException e) {
                    log.error("VENTA failed: {}", e.getMessage());
                    ctx.status(502).json(ApiError.of("PeticionException", e.getMessage()));
                } catch (Exception e) {
                    log.error("VENTA unexpected error", e);
                    ctx.status(500).json(ApiError.of(
                            e.getClass().getSimpleName(),
                            e.getMessage() == null ? "Error inesperado" : e.getMessage()));
                }
            }
        };
    }

    public Handler ventaQR() {
        return new Handler() {
            @Override
            public void handle(@NotNull Context ctx) {
                VentaRequest req = ctx.bodyAsClass(VentaRequest.class);
                if (req.importe() == null || req.importe().isBlank()) {
                    ctx.status(400).json(ApiError.badRequest("Falta 'importe'"));
                    return;
                }

                Map<PARAMETRO_OPERACION, Object> parametros = new HashMap<>();
                parametros.put(PARAMETRO_OPERACION.IMPORTE, req.importe());
                if (req.hasTip()) {
                    parametros.put(PARAMETRO_OPERACION.PROPINA, req.propina());
                }

                try {
                    // `new Peticion()` itself declares throws PeticionException.
                    Peticion p = new Peticion();
                    p.setOperador(sdk.operador());
                    p.setFecha(DATE_FMT.get().format(new Date()));
                    p.setOperacion(OPERACION.VENTA_QR, parametros);

                    currentQrPeticion = p;
                    Respuesta resp = p.autorizar();
                    log.info("VENTA_QR codigoRespuesta={} leyenda={} importe={}",
                            resp.getCodigoRespuesta(), resp.getLeyenda(), req.importe());
                    String voucherText = null;
                    if ("00".equals(resp.getCodigoRespuesta())) {
                        dao.persist("VENTA_QR", resp);
                        Map<String, Object> tx = dao.findByIdTransaccion(resp.getIdTransaccion());
                        if (tx != null) {
                            voucherText = VoucherFormatter.build(tx, sdk.operador(), null);
                        }
                    }
                    ctx.status(200).json(RespuestaDto.from(resp, voucherText));

                } catch (PeticionException e) {
                    // Code "99" = QR session timed out without payment; mandatory
                    // to call consultar afterwards per the manual page 31 to avoid
                    // double charging. Surface as 502 with a hint in the message.
                    log.warn("VENTA_QR failed: {}", e.getMessage());
                    ctx.status(502).json(ApiError.of("PeticionException", e.getMessage()));
                } catch (Exception e) {
                    log.error("VENTA_QR unexpected error", e);
                    ctx.status(500).json(ApiError.of(
                            e.getClass().getSimpleName(),
                            e.getMessage() == null ? "Error inesperado" : e.getMessage()));
                } finally {
                    currentQrPeticion = null;
                }
            }
        };
    }

    public Handler cancelarQR() {
        return new Handler() {
            @Override
            public void handle(@NotNull Context ctx) {
                Peticion p = currentQrPeticion;
                if (p == null) {
                    ctx.status(404).json(ApiError.of(
                            "NoActiveQr",
                            "No hay una venta QR activa para cancelar"));
                    return;
                }
                try {
                    p.finalizarOperacionQR();
                    log.info("VENTA_QR cancelada por operador (finalizarOperacionQR)");
                    ctx.status(200).json(Map.of("success", true));
                } catch (Exception e) {
                    log.error("cancelarQR failed", e);
                    ctx.status(500).json(ApiError.of(
                            e.getClass().getSimpleName(),
                            e.getMessage() == null ? "Error cancelando QR" : e.getMessage()));
                }
            }
        };
    }
}
