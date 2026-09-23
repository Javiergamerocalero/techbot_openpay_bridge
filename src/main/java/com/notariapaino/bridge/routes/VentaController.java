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

/** Sale endpoints for card and QR payments. */
public final class VentaController {

    private static final Logger log = LoggerFactory.getLogger(VentaController.class);
    private static final ThreadLocal<SimpleDateFormat> DATE_FMT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyyMMddHHmmss"));
    private static final int CUOTAS_PCI_TOPE = 12;

    private final SdkManager sdk;
    private final TransactionDao dao;
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
                    Peticion p = new Peticion();
                    p.setOperador(sdk.operador());
                    p.setFecha(DATE_FMT.get().format(new Date()));
                    p.setOperacion(OPERACION.VENTA, parametros);

                    // Preserve the newer explicit-request contract, while also
                    // restoring the production 1.1.9 automatic promotion flow
                    // when the kiosk did not request installments explicitly.
                    if (req.hasInstallments()) {
                        Tarjeta tarjeta = p.leerTarjeta();
                        PROMOCION promo = req.isSinIntereses()
                                ? PROMOCION.MESES_SIN_INTERESES
                                : PROMOCION.MESES_CON_INTERESES;
                        p.setPromocionMeses(promo, req.cuotas());
                        log.info("VENTA installments explicit: cuotas={}, promo={}, productoTarjeta={}",
                                req.cuotas(), promo, tarjeta == null ? "?" : tarjeta.getProducto());
                    } else {
                        habilitarPromocionSiAplica(p);
                    }

                    Respuesta resp = p.autorizar();
                    log.info("VENTA codigoRespuesta={} leyenda={} importe={}",
                            resp.getCodigoRespuesta(), resp.getLeyenda(), req.importe());
                    String voucherText = null;
                    if ("00".equals(resp.getCodigoRespuesta())) {
                        dao.persist("VENTA", resp);
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

    /**
     * Production behavior recovered from Bridge 1.1.9.
     * Credit cards are inspected before authorization. PSI uses the maximum
     * number of interest-free installments reported by the card/host; PCI
     * falls back to the 12-installment ceiling used in production.
     */
    private void habilitarPromocionSiAplica(Peticion p) throws PeticionException {
        Tarjeta tarjeta = p.leerTarjeta();
        if (tarjeta == null) {
            log.warn("VENTA leerTarjeta returned null; continuing without promotion");
            return;
        }

        String producto = tarjeta.getProducto();
        int maxCuotasPsi = tarjeta.getMaxCuotasPsi();
        boolean cuotasPci = tarjeta.isCuotasPci();
        log.info("VENTA card promotion probe: producto={}, maxCuotasPsi={}, cuotasPci={}",
                producto, maxCuotasPsi, cuotasPci);

        if (!"C".equalsIgnoreCase(producto)) {
            return;
        }

        if (maxCuotasPsi > 0) {
            p.setPromocionMeses(PROMOCION.MESES_SIN_INTERESES, maxCuotasPsi);
            log.info("VENTA automatic promotion: PSI {} cuotas", maxCuotasPsi);
        } else if (cuotasPci) {
            p.setPromocionMeses(PROMOCION.MESES_CON_INTERESES, CUOTAS_PCI_TOPE);
            log.info("VENTA automatic promotion: PCI {} cuotas", CUOTAS_PCI_TOPE);
        } else {
            log.info("VENTA credit card without installment promotion; continuing as regular sale");
        }
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
