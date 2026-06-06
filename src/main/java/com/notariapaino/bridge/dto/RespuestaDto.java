package com.notariapaino.bridge.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON envelope serializing the SDK's {@code Respuesta} object (which itself
 * is a {@code HashMap<String, Object>} populated by the SDK's
 * {@code Respuesta(Transaction, version)} constructor) so the kiosk Flutter
 * client gets a stable shape.
 *
 * <p>We expose:
 * <ul>
 *   <li>{@code success} — convenience flag, true when {@code codigoRespuesta == "00"}
 *       (or {@code "D1"} for QR voids, which the SDK treats as approved too).</li>
 *   <li>{@code codigoRespuesta} / {@code leyenda} — the two universally
 *       interesting fields, lifted out of {@code data} for easy access in UI.</li>
 *   <li>{@code data} — full keyed payload from the SDK Respuesta. Includes
 *       everything documented on page 48 of the SDK manual: idTransaccion,
 *       autorizacion, referenciaFinanciera, numeroTarjeta, tarjetahabiente,
 *       modoLectura, criptogramaTarjeta, idAplicacionTarjeta, importe,
 *       firma, fechaHora, serieTerminal, etc. PLUS our injected
 *       {@code voucherText} when the caller pre-formats it.</li>
 * </ul>
 *
 * <p>The keys inside {@code data} match the SDK's property names exactly
 * (see {@code Respuesta} class methods on page 54-55) so the client can use
 * any of them without translation. The injected {@code voucherText} key is
 * NOT from the SDK — it's the bridge's pre-rendered printable voucher,
 * computed via {@code VoucherFormatter} immediately after persistence so
 * the kiosk receives one round-trip-free string ready to send to the
 * printer.
 */
public record RespuestaDto(
        boolean success,
        String codigoRespuesta,
        String leyenda,
        Map<String, Object> data
) {

    /** Convenience overload — used by code paths that don't pre-build a voucher (CONSULTA, CARGA_LLAVES). */
    public static RespuestaDto from(Map<String, Object> respuesta) {
        return from(respuesta, null);
    }

    /**
     * Build a {@link RespuestaDto} from the SDK's {@code Respuesta} map,
     * optionally with a pre-rendered voucher text. When {@code voucherText}
     * is non-null, it goes into {@code data.voucherText} so the kiosk can
     * consume it without making a second HTTP call.
     */
    public static RespuestaDto from(Map<String, Object> respuesta, String voucherText) {
        Map<String, Object> data = new LinkedHashMap<>(respuesta);
        if (voucherText != null && !voucherText.isBlank()) {
            data.put("voucherText", voucherText);
        }
        String code = String.valueOf(data.getOrDefault("codigoRespuesta", ""));
        String legend = String.valueOf(data.getOrDefault("leyenda", ""));
        boolean ok = "00".equals(code) || "D1".equals(code);
        return new RespuestaDto(ok, code, legend, data);
    }
}
