package com.notariapaino.bridge.dto;

/**
 * Standard error envelope returned with non-2xx responses. The kiosk
 * client (Flutter) deserializes this so it can show a useful message
 * to the operator.
 *
 * <p>{@code code} is a short stable identifier (e.g. {@code PinPadKeysException},
 * {@code BadRequest}, {@code HostTimeout}) — useful for switch statements in
 * the client. {@code message} is human-readable Spanish text intended for the
 * operator. {@code detail} carries optional verbose info (stack-trace gist,
 * URL of upstream call that failed, etc.) for diagnostics.
 */
public record ApiError(
        String code,
        String message,
        String detail
) {
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, null);
    }

    public static ApiError badRequest(String message) {
        return new ApiError("BadRequest", message, null);
    }

    public static ApiError requiresKeyLoad() {
        return new ApiError(
                "PinPadKeysException",
                "El PinPad requiere carga de llaves. Ejecute POST /api/carga-llaves.",
                null);
    }
}
