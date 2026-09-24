package com.techbot.openpay.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El enfriamiento entre reinicios existe para que un Bridge que no logra
 * arrancar no entre en bucle de reinicios. Lo comprobaba solo la ruta bajo
 * demanda: el self-check de arranque reiniciaba igual, aunque el quiosco
 * acabara de pedir una recuperacion. Estas pruebas fijan la regla que ahora
 * usan los dos caminos.
 */
final class RestartCooldownTest {

    private static final int COOLDOWN = 120;
    private static final long AHORA = 1_700_000_000_000L;

    @Test
    @DisplayName("sin reinicios previos no hay enfriamiento")
    void sinReinicioPrevio() {
        assertFalse(RecoveryMain.cooldownActive(0, AHORA, COOLDOWN));
    }

    @Test
    @DisplayName("un reinicio reciente bloquea el siguiente")
    void reinicioReciente() {
        long hace7s = AHORA - 7_000;
        assertTrue(RecoveryMain.cooldownActive(hace7s, AHORA, COOLDOWN));
        assertEquals(113, RecoveryMain.cooldownRemainingSeconds(hace7s, AHORA, COOLDOWN));
    }

    @Test
    @DisplayName("cumplido el enfriamiento se puede reintentar")
    void enfriamientoCumplido() {
        assertFalse(RecoveryMain.cooldownActive(AHORA - 120_000, AHORA, COOLDOWN));
        assertFalse(RecoveryMain.cooldownActive(AHORA - 500_000, AHORA, COOLDOWN));
    }

    @Test
    @DisplayName("en el ultimo segundo nunca informa 0")
    void nuncaInformaCero() {
        long casiCumplido = AHORA - 119_500;
        assertTrue(RecoveryMain.cooldownActive(casiCumplido, AHORA, COOLDOWN));
        assertEquals(1, RecoveryMain.cooldownRemainingSeconds(casiCumplido, AHORA, COOLDOWN));
    }

    @Test
    @DisplayName("el reloj hacia atras no desactiva el enfriamiento")
    void relojHaciaAtras() {
        // Un ajuste de hora puede dejar el ultimo reinicio en el futuro.
        assertTrue(RecoveryMain.cooldownActive(AHORA + 30_000, AHORA, COOLDOWN));
    }
}
