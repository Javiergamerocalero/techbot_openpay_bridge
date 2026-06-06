package com.notariapaino.bridge.voucher;

import java.util.Map;

/**
 * Pure function that turns a persisted transaction row (the flat
 * {@code Map<String,Object>} that {@code TransactionDao} produces) into
 * the printable monospace text for the BBVA TotalPOS voucher.
 *
 * <p>Used in two places:
 * <ul>
 *   <li>Inline at venta/anulación time: the controller calls
 *       {@link #build} right after {@code dao.persist()} and stuffs the
 *       result into the response payload as {@code voucherText} so the
 *       kiosk can send it straight to the MW's printer service.</li>
 *   <li>On reprint: the {@code VoucherController} GET endpoint builds the
 *       same text on demand from the cached row.</li>
 * </ul>
 *
 * <p>Width is fixed at {@value #WIDTH} columns — the BBVA-spec'd width for
 * 58mm thermal printers, which is what the kiosk uses. If you ever move
 * to an 80mm printer, change WIDTH here and the layout adapts on its own
 * (centered headers re-center, dividers re-stretch).
 */
public final class VoucherFormatter {

    /** 58mm thermal printer monospace column count. */
    public static final int WIDTH = 32;

    private VoucherFormatter() {}

    /**
     * Compose the voucher text.
     *
     * @param tx                   row from {@code TransactionDao.findByIdTransaccion},
     *                             flat Map of fields. Must NOT be null.
     * @param operador             SDK operator id (e.g. "KIOSK02") — printed
     *                             on the voucher. Pass empty string if unknown.
     * @param razonSocialOverride  optional header text. When non-blank,
     *                             overrides the default "NOTARIA PAINO".
     *                             Lets the kiosk swap the merchant name
     *                             without restarting the bridge.
     */
    public static String build(Map<String, Object> tx,
                                String operador,
                                String razonSocialOverride) {
        String header = (razonSocialOverride != null && !razonSocialOverride.isBlank())
                ? razonSocialOverride.toUpperCase()
                : "NOTARIA PAINO";

        StringBuilder b = new StringBuilder();
        line(b, center(header));
        line(b, divider('='));
        line(b, center(tipoToTitle(s(tx.get("tipo")))));
        line(b, divider('-'));
        line(b, "Fecha: " + formatFecha(s(tx.get("fechaHora"))));
        line(b, "Hora:  " + formatHora(s(tx.get("fechaHora"))));
        String numTerm = s(tx.get("numeroTerminal"));
        if (!numTerm.isEmpty()) line(b, "Terminal: " + numTerm);
        String serie = s(tx.get("serieTerminal"));
        if (!serie.isEmpty()) line(b, "Serie: " + serie);
        if (operador != null && !operador.isBlank()) {
            line(b, "Operador: " + operador);
        }
        line(b, divider('-'));
        String tarjeta = s(tx.get("numeroTarjeta"));
        if (!tarjeta.isEmpty()) line(b, "Tarjeta: " + tarjeta);
        String aplic = s(tx.get("aplicacionTarjeta"));
        if (!aplic.isEmpty()) line(b, "Marca: " + aplic);
        String titular = s(tx.get("tarjetahabiente"));
        if (!titular.isEmpty()) line(b, "Titular: " + titular);
        line(b, divider('-'));
        line(b, "Importe: " + formatImporte(s(tx.get("importe")), s(tx.get("moneda"))));
        String aut = s(tx.get("autorizacion"));
        if (!aut.isEmpty()) line(b, "Aut: " + aut);
        String ref = s(tx.get("referenciaFinanciera"));
        if (!ref.isEmpty()) line(b, "Ref: " + ref);
        line(b, divider('-'));
        line(b, center(s(tx.get("leyenda"))));
        String firma = s(tx.get("firma"));
        if (!firma.isEmpty()) {
            line(b, divider('-'));
            line(b, "Firma: " + firma);
        }
        line(b, divider('='));
        line(b, center("COPIA TARJETAHABIENTE"));
        return b.toString();
    }

    // ─── Helpers ────────────────────────────────────────────

    private static String tipoToTitle(String tipo) {
        if (tipo == null) return "OPERACION";
        return switch (tipo) {
            case "VENTA" -> "VENTA APROBADA";
            case "VENTA_QR" -> "VENTA QR APROBADA";
            case "ANULACION_VENTA_TARJETA" -> "ANULACION VENTA";
            case "ANULACION_VENTA_QR" -> "ANULACION VENTA QR";
            default -> tipo;
        };
    }

    /** {@code yyyyMMddHHmmss} → {@code dd/MM/yyyy}. */
    private static String formatFecha(String yyyymmddhhmmss) {
        if (yyyymmddhhmmss == null || yyyymmddhhmmss.length() < 8) return "";
        String yyyy = yyyymmddhhmmss.substring(0, 4);
        String mm = yyyymmddhhmmss.substring(4, 6);
        String dd = yyyymmddhhmmss.substring(6, 8);
        return dd + "/" + mm + "/" + yyyy;
    }

    /** {@code yyyyMMddHHmmss} → {@code HH:mm:ss}. */
    private static String formatHora(String yyyymmddhhmmss) {
        if (yyyymmddhhmmss == null || yyyymmddhhmmss.length() < 14) return "";
        String hh = yyyymmddhhmmss.substring(8, 10);
        String mm = yyyymmddhhmmss.substring(10, 12);
        String ss = yyyymmddhhmmss.substring(12, 14);
        return hh + ":" + mm + ":" + ss;
    }

    private static String formatImporte(String importe, String moneda) {
        String simbolo = "DOLARES".equals(moneda) ? "$ " : "S/ ";
        return simbolo + (importe == null ? "0.00" : importe);
    }

    private static String s(Object o) {
        return o == null ? "" : o.toString();
    }

    private static void line(StringBuilder b, String content) {
        b.append(content).append('\n');
    }

    private static String divider(char ch) {
        return String.valueOf(ch).repeat(WIDTH);
    }

    private static String center(String text) {
        if (text == null) return "";
        if (text.length() >= WIDTH) return text.substring(0, WIDTH);
        int pad = (WIDTH - text.length()) / 2;
        return " ".repeat(pad) + text;
    }
}
