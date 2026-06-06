package com.notariapaino.bridge.routes;

import com.notariapaino.bridge.SdkManager;
import com.notariapaino.bridge.db.TransactionDao;
import com.notariapaino.bridge.voucher.VoucherFormatter;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Voucher reprint endpoint: {@code GET /api/transactions/{id}/voucher}.
 *
 * <p>Builds the printable voucher on demand from the SQLite cache by
 * delegating to {@link VoucherFormatter}. The same formatter is invoked
 * inline by {@code VentaController} / {@code AnulacionController} at sale
 * time so the kiosk can print the voucher in the same flow — this endpoint
 * is the manual reprint button on the Anulaciones screen.
 *
 * <p>Response shape:
 *
 * <pre>{@code
 * 200 OK  → {"success": true, "voucherText": "...full text..."}
 * 404     → {"success": false, "error": "Transacción no encontrada"}
 * }</pre>
 */
public final class VoucherController {

    private static final Logger log = LoggerFactory.getLogger(VoucherController.class);

    private final TransactionDao dao;
    private final SdkManager sdk;

    public VoucherController(TransactionDao dao, SdkManager sdk) {
        this.dao = dao;
        this.sdk = sdk;
    }

    public Handler voucher() {
        return new Handler() {
            @Override
            public void handle(@NotNull Context ctx) {
                String idTx = ctx.pathParam("id");
                String razonSocialOverride = ctx.queryParam("razonSocial");

                Map<String, Object> tx = dao.findByIdTransaccion(idTx);
                if (tx == null) {
                    log.warn("voucher({}) not found in SQLite cache", idTx);
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("success", false);
                    body.put("error", "Transacción no encontrada");
                    ctx.status(404).json(body);
                    return;
                }

                String voucherText = VoucherFormatter.build(tx, safeOperador(), razonSocialOverride);
                log.info("voucher({}) returned {} chars", idTx, voucherText.length());

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", true);
                body.put("voucherText", voucherText);
                ctx.status(200).json(body);
            }
        };
    }

    private String safeOperador() {
        try {
            String op = sdk.operador();
            return op == null ? "" : op;
        } catch (Exception e) {
            return "";
        }
    }
}
