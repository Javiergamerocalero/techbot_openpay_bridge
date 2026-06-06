package com.notariapaino.bridge.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure aggregation of the bridge's SQLite-cached shift transactions into the
 * "totales" block the kiosk's Flutter UI consumes.
 *
 * <p>Used by:
 * <ul>
 *   <li>{@code ReportesController.totalizado / detallado} — live shift reports.</li>
 *   <li>{@code CierreController} — snapshot the totals just before the
 *       {@code clearShift()} call so the cierre response can carry the
 *       per-shift numbers back to the kiosk.</li>
 * </ul>
 *
 * <p>Shape returned (matches what
 * {@code TotalPosReporteTotalizado.fromJson} reads in the Flutter client):
 *
 * <pre>{@code
 * {
 *   "totalSoles": 300.92,
 *   "totalDolares": 0.0,
 *   "cantidadVentas": 3,
 *   "cantidadAnulaciones": 0,
 *   "anuladoSoles": 0.0,
 *   "anuladoDolares": 0.0,
 *   "netoSoles": 300.92,
 *   "netoDolares": 0.0,
 *   "idTurno": ""
 * }
 * }</pre>
 */
public final class ReportAggregator {

    private ReportAggregator() {}

    public static Map<String, Object> aggregateTotals(List<Map<String, Object>> rows) {
        double totalSoles = 0, totalDolares = 0;
        double anuladoSoles = 0, anuladoDolares = 0;
        int cantidadVentas = 0, cantidadAnulaciones = 0;

        for (Map<String, Object> r : rows) {
            String tipo = String.valueOf(r.get("tipo"));
            String moneda = String.valueOf(r.get("moneda"));
            double importe = parseImporte(r.get("importe"));

            boolean isVenta = "VENTA".equals(tipo) || "VENTA_QR".equals(tipo);
            boolean isAnulacion = "ANULACION_VENTA_TARJETA".equals(tipo)
                    || "ANULACION_VENTA_QR".equals(tipo);

            if (isVenta) {
                cantidadVentas++;
                if ("SOLES".equals(moneda)) totalSoles += importe;
                else if ("DOLARES".equals(moneda)) totalDolares += importe;
                // NOTA: NO sumamos al anulado aunque la venta esté marcada
                // anulada=true. La anulación tiene su propio registro en la
                // tabla (insertado por AnulacionController.persist), y ESE
                // es el que cuenta para anuladoSoles/Dolares. Sumar acá
                // adicionalmente causa doble contado (venta S/ 47.67 anulada
                // sumaría 95.34 al anulado en vez de 47.67).
            } else if (isAnulacion) {
                cantidadAnulaciones++;
                if ("SOLES".equals(moneda)) anuladoSoles += importe;
                else if ("DOLARES".equals(moneda)) anuladoDolares += importe;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalSoles", round2(totalSoles));
        out.put("totalDolares", round2(totalDolares));
        out.put("cantidadVentas", cantidadVentas);
        out.put("cantidadAnulaciones", cantidadAnulaciones);
        out.put("anuladoSoles", round2(anuladoSoles));
        out.put("anuladoDolares", round2(anuladoDolares));
        out.put("netoSoles", round2(totalSoles - anuladoSoles));
        out.put("netoDolares", round2(totalDolares - anuladoDolares));
        out.put("idTurno", "");
        return out;
    }

    /** Importe comes as a string in the SDK's format (e.g. "754.00"). */
    private static double parseImporte(Object raw) {
        if (raw == null) return 0;
        try {
            return Double.parseDouble(raw.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
