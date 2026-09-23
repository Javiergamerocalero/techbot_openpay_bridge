package com.notariapaino.bridge.voucher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Formats a persisted transaction row into printable voucher text.
 *
 * <p>The 46-column layout and promotion/QR handling are retained from the
 * production Bridge 1.1.9. Merchant identity always comes from the SDK (or
 * an explicit override); there is intentionally no hard-coded merchant name.
 */
public final class VoucherFormatter {

    public static final int WIDTH = 46;

    private VoucherFormatter() {}

    public static String build(Map<String, Object> tx,
                               String operador,
                               String razonSocialOverride) {
        StringBuilder b = new StringBuilder();

        String tipo = s(tx.get("tipo"));
        boolean isAnulacion = isAnulacionTipo(tipo);
        boolean isQr = "VENTA_QR".equals(tipo)
                || "ANULACION_VENTA_QR".equals(tipo)
                || "03".equals(s(tx.get("modoLectura")));

        line(b, center(isAnulacion ? "ANULACION" : "VENTA"));
        line(b, center("OPENPAY BY BBVA"));
        line(b, center(formatFechaHora(s(tx.get("fechaHora")))));

        String comercio = pickComercio(tx, razonSocialOverride);
        if (!comercio.isBlank()) line(b, center(comercio));

        String ref = s(tx.get("referenciaFinanciera"));
        if (!ref.isEmpty()) line(b, center("REF: " + ref));

        String aut = s(tx.get("autorizacion"));
        if (!aut.isEmpty()) line(b, center("APP: " + aut));

        String tarjeta = s(tx.get("numeroTarjeta"));
        if (!tarjeta.isEmpty()) line(b, center("TC: " + tarjeta));

        String importe = s(tx.get("importe"));
        if (!importe.isEmpty()) {
            line(b, center("TOTAL: " + formatImporte(importe, s(tx.get("moneda")))));
        }

        appendPromocionLines(b, tx);

        if (isQr) {
            line(b, center("PAGO CON QR"));
            String appLabel = s(tx.get("aplicacionTarjeta"));
            if (!appLabel.isEmpty()) line(b, center("APP LABEL: " + appLabel));
        } else {
            String firma = firmaLabel(s(tx.get("firma")));
            if (!firma.isEmpty()) {
                for (String segment : wrapCentered(firma)) line(b, segment);
            }

            String aid = s(tx.get("idAplicacionTarjeta"));
            if (!aid.isEmpty()) line(b, center("AID: " + aid));

            String appLabel = s(tx.get("aplicacionTarjeta"));
            if (!appLabel.isEmpty()) line(b, center("APP LABEL: " + appLabel));

            String cripto = s(tx.get("criptogramaTarjeta"));
            if (!cripto.isEmpty()) line(b, center("CRYPTO : " + formatHexWithSpaces(cripto)));
        }

        Object anuladaFlag = tx.get("anulada");
        boolean anulada = anuladaFlag instanceof Boolean && (Boolean) anuladaFlag;
        if (anulada && !isAnulacion) {
            line(b, "");
            line(b, center("*** ANULADA ***"));
        }

        b.append('\n').append('\n');
        return b.toString();
    }

    private static String pickComercio(Map<String, Object> tx, String override) {
        if (override != null && !override.isBlank()) return override.trim().toUpperCase();
        String razon = s(tx.get("razonSocial"));
        return razon.isBlank() ? "" : razon.trim().toUpperCase();
    }

    private static void appendPromocionLines(StringBuilder b, Map<String, Object> tx) {
        String mensajes = s(tx.get("mensajesPromocion"));
        if (!mensajes.isBlank()) {
            for (String mensaje : mensajes.split("\\|")) {
                String trimmed = mensaje.trim();
                if (!trimmed.isEmpty()) line(b, center(trimmed));
            }
        }

        String codigo = s(tx.get("codigoPromocion"));
        String cuotas = s(tx.get("cuotas"));
        String montoCuota = s(tx.get("montoCuota"));

        if (!cuotas.isBlank() && !"0".equals(cuotas) && !"1".equals(cuotas)) {
            StringBuilder label = new StringBuilder("CUOTAS: ").append(cuotas);
            if ("MESES_SIN_INTERESES".equalsIgnoreCase(codigo)) {
                label.append(" SIN INTERESES");
            } else if ("MESES_CON_INTERESES".equalsIgnoreCase(codigo)) {
                label.append(" CON INTERESES");
            }
            line(b, center(label.toString()));

            if (!montoCuota.isBlank() && !"0".equals(montoCuota) && !"0.00".equals(montoCuota)) {
                line(b, center("MONTO CUOTA: " + formatImporte(montoCuota, s(tx.get("moneda")))));
            }
        }
    }

    private static boolean isAnulacionTipo(String tipo) {
        return tipo != null && tipo.startsWith("ANULACION");
    }

    private static String firmaLabel(String firma) {
        if (firma == null || firma.isBlank()) return "";
        return switch (firma.toUpperCase()) {
            case "SIN_FIRMA", "PAGO_RAPIDO", "PAGORAPIDO" ->
                    "PAGO RAPIDO, NO REQUIERE PIN NI FIRMA";
            case "ELECTRONICA" -> "PIN VERIFICADO";
            case "AUTOGRAFA", "CON_FIRMA", "REQUIERE_FIRMA" ->
                    "REQUIERE FIRMA FISICA";
            default -> "";
        };
    }

    private static String formatFechaHora(String yyyymmddhhmmss) {
        if (yyyymmddhhmmss == null || yyyymmddhhmmss.length() < 12) return "";
        return yyyymmddhhmmss.substring(6, 8) + "/"
                + yyyymmddhhmmss.substring(4, 6) + "/"
                + yyyymmddhhmmss.substring(0, 4) + " "
                + yyyymmddhhmmss.substring(8, 10) + ":"
                + yyyymmddhhmmss.substring(10, 12);
    }

    private static String formatImporte(String importe, String moneda) {
        String simbolo = "DOLARES".equalsIgnoreCase(moneda) ? "$ " : "S/ ";
        return simbolo + (importe == null ? "0.00" : importe);
    }

    private static String formatHexWithSpaces(String value) {
        if (value == null) return "";
        String clean = value.replaceAll("\\s+", "");
        if (clean.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < clean.length(); i += 2) {
            if (i > 0) out.append(' ');
            out.append(clean, i, Math.min(i + 2, clean.length()));
        }
        return out.toString().toUpperCase();
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

    private static List<String> wrapCentered(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            int needed = current.length() == 0
                    ? word.length()
                    : current.length() + 1 + word.length();
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
