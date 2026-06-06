package com.notariapaino.bridge.dto;

/**
 * Request body for {@code POST /api/venta}.
 *
 * <ul>
 *   <li>{@code importe} — total amount as string with 2 decimals, e.g. {@code "100.00"}.
 *       Matches the SDK's {@code PARAMETRO_OPERACION.IMPORTE} contract.</li>
 *   <li>{@code propina} — optional tip amount, same format. {@code null} or empty
 *       means no tip.</li>
 *   <li>{@code cuotas} — optional installments count (Mastercard / VISA credit).
 *       When &gt; 0, the SDK reads the card first and applies
 *       {@code setPromocionMeses(MESES_CON_INTERESES, cuotas)}.</li>
 *   <li>{@code sinIntereses} — when {@code true} combined with {@code cuotas},
 *       sets {@code MESES_SIN_INTERESES} (PSI) instead of regular installments.</li>
 * </ul>
 */
public record VentaRequest(
        String importe,
        String propina,
        Integer cuotas,
        Boolean sinIntereses
) {
    /** Reasonable defaults when caller omits optional fields. */
    public boolean hasTip() {
        return propina != null && !propina.isBlank() && !"0".equals(propina) && !"0.00".equals(propina);
    }

    public boolean hasInstallments() {
        return cuotas != null && cuotas > 0;
    }

    public boolean isSinIntereses() {
        return Boolean.TRUE.equals(sinIntereses);
    }
}
