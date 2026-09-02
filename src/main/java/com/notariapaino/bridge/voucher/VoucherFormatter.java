package com.notariapaino.bridge.voucher;

import java.util.Map;

/**
 * Formats a persisted transaction row into the printable voucher text.
 *
 * <p><b>Diseño (Javier 2026-09-02):</b> reproduce el layout OFICIAL que
 * el SDK BBVA imprime en su propia impresora integrada — el kiosko usa
 * su impresora externa vía el kiosk client, así que necesitamos armar
 * el mismo texto acá para que ambas vías luzcan idéntico. Antes esto
 * usaba una plantilla local hardcoded a "NOTARIA PAINO" con un layout
 * inventado, lo cual quedó desactualizado cuando se puso en operación
 * en Delibakery (el operador veía "NOTARIA PAINO" en el voucher).
 *
 * <p>Ahora la plantilla es la de OPENPAY BY BBVA, con los datos que
 * vienen directo de la {@code Respuesta} del SDK y que persistimos en
 * {@code TransactionDao}:
 *
 * <pre>
 *          VENTA
 *      OPENPAY BY BBVA
 *      dd/MM/yyyy HH:mm
 *   &lt;RAZON SOCIAL&gt;                &lt;- de resp.getRazonSocial() (BBVA host)
 *   REF: &lt;referencia&gt;
 *   APP: &lt;autorizacion&gt;
 *   TC:  ************1234
 *   TOTAL: S/ 12.90
 *   PAGO RAPIDO, NO REQUIERE PIN NI FIRMA   &lt;- si firma dice sin firma
 *   AID:       A0000000031010
 *   APP LABEL: VISA DEBITO
 *   CRYPTO:    7D 33 AE 71 7F BA 2D F0
 * </pre>
 *
 * <p>Para anulaciones el header cambia a {@code ANULACION DE VENTA} /
 * {@code ANULACION DE VENTA QR} pero el resto del cuerpo se mantiene
 * idéntico (mismo shape para que caja y contabilidad los reconozcan).
 *
 * <p>Ancho fijo {@value #WIDTH} columnas para papel de 58mm. Cambiando
 * WIDTH la maquetación se re-centra sola.
 */
public final class VoucherFormatter {

    /** 58mm thermal printer monospace column count. */
    public static final int WIDTH = 32;

    private VoucherFormatter() {}

    /**
     * Build the printable voucher text.
     *
     * @param tx                   row persisted by {@code TransactionDao}. Never null.
     * @param operador             ignored (kept for API compat).
     * @param razonSocialOverride  optional razón social. Si es blank, se
     *                             usa la que trae la persistencia
     *                             ({@code razonSocial} de la Respuesta).
     */
    public static String build(Map<String, Object> tx,
                                String operador,
                                String razonSocialOverride) {
        StringBuilder b = new StringBuilder();

        // Cabecera
        line(b, center(tipoToTitle(s(tx.get("tipo")))));
        line(b, center("OPENPAY BY BBVA"));
        line(b, center(formatFechaHora(s(tx.get("fechaHora")))));

        String razon = (razonSocialOverride != null && !razonSocialOverride.isBlank())
                ? razonSocialOverride
                : s(tx.get("razonSocial"));
        if (!razon.isBlank()) {
            line(b, center(razon.toUpperCase()));
        }

        // Cuerpo del voucher.
        String ref = s(tx.get("referenciaFinanciera"));
        if (!ref.isEmpty()) line(b, "REF: " + ref);
        String aut = s(tx.get("autorizacion"));
        if (!aut.isEmpty()) line(b, "APP: " + aut);
        String tarjeta = s(tx.get("numeroTarjeta"));
        if (!tarjeta.isEmpty()) line(b, "TC:  " + tarjeta);
        String importe = s(tx.get("importe"));
        if (!importe.isEmpty()) {
            line(b, "TOTAL: " + formatImporte(importe, s(tx.get("moneda"))));
        }

        String firma = s(tx.get("firma"));
        String firmaText = firmaToVoucherText(firma);
        if (firmaText != null) {
            // Puede pasar de 32 col (ej. "PAGO RAPIDO, NO REQUIERE PIN
            // NI FIRMA" = 39). Envolvemos en 2+ líneas centradas.
            for (String seg : wrapCentered(firmaText)) {
                line(b, seg);
            }
        }

        String aid = s(tx.get("idAplicacionTarjeta"));
        if (!aid.isEmpty()) line(b, "AID:       " + aid);
        String appLabel = s(tx.get("aplicacionTarjeta"));
        if (!appLabel.isEmpty()) line(b, "APP LABEL: " + appLabel);
        String cripto = s(tx.get("criptogramaTarjeta"));
        if (!cripto.isEmpty()) line(b, "CRYPTO:    " + formatCriptograma(cripto));

        // Pie: si la venta fue anulada (marcada en la DB por
        // markAnuladaByReferencia) lo declaramos así para que el operador
        // sepa que ese voucher ya no vale como comprobante de pago.
        Object anuladaFlag = tx.get("anulada");
        boolean anulada = anuladaFlag instanceof Boolean && (Boolean) anuladaFlag;
        if (anulada && !isAnulacionTipo(s(tx.get("tipo")))) {
            line(b, "");
            line(b, center("*** ANULADA ***"));
        }

        return b.toString();
    }

    // ─── Helpers ────────────────────────────────────────────

    private static String tipoToTitle(String tipo) {
        if (tipo == null) return "OPERACION";
        return switch (tipo) {
            case "VENTA" -> "VENTA";
            case "VENTA_QR" -> "VENTA QR";
            case "ANULACION_VENTA_TARJETA" -> "ANULACION DE VENTA";
            case "ANULACION_VENTA_QR" -> "ANULACION DE VENTA QR";
            default -> tipo;
        };
    }

    private static boolean isAnulacionTipo(String tipo) {
        return tipo != null && tipo.startsWith("ANULACION");
    }

    /**
     * Convierte {@code SIN_FIRMA} / {@code CON_FIRMA} / etc del SDK BBVA
     * al texto que aparece en el voucher físico. {@code null} = no
     * imprimir esa línea (ej. QR sin firma no aplica).
     */
    private static String firmaToVoucherText(String firma) {
        if (firma == null || firma.isBlank()) return null;
        return switch (firma.toUpperCase()) {
            case "SIN_FIRMA", "PAGO_RAPIDO", "PAGORAPIDO" ->
                "PAGO RAPIDO, NO REQUIERE PIN NI FIRMA";
            case "CON_FIRMA", "REQUIERE_FIRMA" ->
                "REQUIERE FIRMA DEL TARJETAHABIENTE";
            default -> null;
        };
    }

    /** {@code yyyyMMddHHmmss} → {@code dd/MM/yyyy HH:mm}. */
    private static String formatFechaHora(String yyyymmddhhmmss) {
        if (yyyymmddhhmmss == null || yyyymmddhhmmss.length() < 12) return "";
        String yyyy = yyyymmddhhmmss.substring(0, 4);
        String mm = yyyymmddhhmmss.substring(4, 6);
        String dd = yyyymmddhhmmss.substring(6, 8);
        String hh = yyyymmddhhmmss.substring(8, 10);
        String mi = yyyymmddhhmmss.substring(10, 12);
        return dd + "/" + mm + "/" + yyyy + " " + hh + ":" + mi;
    }

    private static String formatImporte(String importe, String moneda) {
        String simbolo = "DOLARES".equals(moneda) ? "$ " : "S/ ";
        return simbolo + (importe == null ? "0.00" : importe);
    }

    /**
     * Formatea el criptograma EMV en pares hex separados por espacio,
     * igual que sale impreso desde el propio SDK ("7D 33 AE 71 7F BA 2D F0").
     * Si ya viene con espacios lo dejamos igual.
     */
    private static String formatCriptograma(String hex) {
        if (hex == null) return "";
        String clean = hex.replaceAll("\\s+", "");
        if (clean.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < clean.length(); i += 2) {
            if (i > 0) sb.append(' ');
            int end = Math.min(i + 2, clean.length());
            sb.append(clean, i, end);
        }
        return sb.toString().toUpperCase();
    }

    private static String s(Object o) {
        return o == null ? "" : o.toString();
    }

    private static void line(StringBuilder b, String content) {
        b.append(content).append('\n');
    }

    private static String center(String text) {
        if (text == null) return "";
        if (text.length() >= WIDTH) return text.substring(0, WIDTH);
        int pad = (WIDTH - text.length()) / 2;
        return " ".repeat(pad) + text;
    }

    /**
     * Word-wrap centrado — parte por espacios acumulando palabras hasta
     * que la siguiente rompería el ancho. Cada línea resultante ya
     * viene padded al centro para ir directo al voucher.
     */
    private static java.util.List<String> wrapCentered(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text == null || text.isBlank()) return out;
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            int needed = current.length() == 0 ? word.length() : current.length() + 1 + word.length();
            if (needed > WIDTH && current.length() > 0) {
                out.add(center(current.toString()));
                current.setLength(0);
            }
            if (current.length() > 0) current.append(' ');
            current.append(word);
        }
        if (current.length() > 0) out.add(center(current.toString()));
        return out;
    }
}
