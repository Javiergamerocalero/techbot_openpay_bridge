package com.notariapaino.bridge.dto;

/**
 * Request body for {@code POST /api/anulacion-tarjeta} and
 * {@code POST /api/anulacion-qr}.
 *
 * <p>Both require the original sale's importe and referenciaFinanciera
 * (a 12-char alphanumeric the host returned on the original venta).
 * The SDK validates the referenciaFinanciera against the regex
 * {@code ^[A-Za-z0-9]{12}$} server-side.
 */
public record AnulacionRequest(
        String importe,
        String referenciaFinanciera
) {
}
