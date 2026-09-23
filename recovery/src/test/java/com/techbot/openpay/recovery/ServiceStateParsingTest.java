package com.techbot.openpay.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * El servicio leia el estado buscando la palabra STATE, que en un Windows en
 * espanol es ESTADO. El Bridge reiniciaba bien y aun asi Recovery respondia
 * service_did_not_reach_RUNNING. Ahora se lee el codigo numerico del Service
 * Control Manager, que es igual en todos los idiomas.
 */
final class ServiceStateParsingTest {

    private static final int STOPPED = 1;
    private static final int RUNNING = 4;
    private static final int NO_STATE = -1;

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "'        STATE              : 4  RUNNING', 4",
        "'        ESTADO             : 4  RUNNING', 4",
        "'        STATE              : 1  STOPPED', 1",
        "'        ESTADO             : 1  STOPPED', 1",
    })
    @DisplayName("lee el estado en ingles y en espanol")
    void leeElEstadoEnAmbosIdiomas(String linea, int esperado) {
        assertEquals(esperado, RecoveryMain.extractServiceStateCode(linea));
    }

    @Test
    @DisplayName("salida completa de sc.exe en ingles")
    void salidaCompletaEnIngles() {
        String salida = String.join("\n",
            "",
            "SERVICE_NAME: TotalPosBridge",
            "        TYPE               : 10  WIN32_OWN_PROCESS",
            "        STATE              : 4  RUNNING",
            "                                (STOPPABLE, NOT_PAUSABLE, ACCEPTS_SHUTDOWN)",
            "        WIN32_EXIT_CODE    : 0  (0x0)",
            "        SERVICE_EXIT_CODE  : 0  (0x0)",
            "        CHECKPOINT         : 0x0",
            "        WAIT_HINT          : 0x0");

        assertEquals(RUNNING, RecoveryMain.extractServiceStateCode(salida));
    }

    /**
     * Copiada literal de un Windows en espanol, incluida la etiqueta TIPO que
     * aparece ANTES del estado: es la que podria confundir al lector si su
     * valor cayera en el rango 1..7.
     */
    @Test
    @DisplayName("salida completa de sc.exe en espanol")
    void salidaCompletaEnEspanol() {
        String salida = String.join("\n",
            "",
            "NOMBRE_SERVICIO: TotalPosBridge ",
            "        TIPO               : 10  WIN32_OWN_PROCESS  ",
            "        ESTADO             : 4  RUNNING ",
            "                                (NOT_STOPPABLE, NOT_PAUSABLE, IGNORES_SHUTDOWN)",
            "        CÓD_SALIDA_WIN32   : 0  (0x0)",
            "        CÓD_SALIDA_SERVICIO: 0  (0x0)",
            "        PUNTO_COMPROB.     : 0x0",
            "        INDICACIÓN_ESPERA  : 0x0");

        assertEquals(RUNNING, RecoveryMain.extractServiceStateCode(salida));
    }

    @Test
    @DisplayName("servicio detenido en espanol")
    void servicioDetenidoEnEspanol() {
        String salida = String.join("\n",
            "NOMBRE_SERVICIO: TotalPosBridge ",
            "        TIPO               : 10  WIN32_OWN_PROCESS  ",
            "        ESTADO             : 1  STOPPED ",
            "        CÓD_SALIDA_WIN32   : 1067  (0x42b)",
            "        CÓD_SALIDA_SERVICIO: 0  (0x0)");

        assertEquals(STOPPED, RecoveryMain.extractServiceStateCode(salida));
    }

    @Test
    @DisplayName("el tipo de servicio no se confunde con el estado")
    void elTipoNoSeConfundeConElEstado() {
        // TIPO 10 va antes que ESTADO 4. Si el lector tomara el primer numero
        // de la salida, devolveria 10 o se quedaria con la linea equivocada.
        String salida = String.join("\n",
            "        TIPO               : 10  WIN32_OWN_PROCESS",
            "        ESTADO             : 1  STOPPED");

        assertEquals(STOPPED, RecoveryMain.extractServiceStateCode(salida));
    }

    @Test
    @DisplayName("el codigo de salida no se confunde con el estado")
    void elCodigoDeSalidaNoSeConfundeConElEstado() {
        // Un arranque fallido deja CÓD_SALIDA_WIN32 en un valor pequeno; el
        // estado va antes, asi que debe ganar el estado.
        String salida = String.join("\n",
            "        TIPO               : 10  WIN32_OWN_PROCESS",
            "        ESTADO             : 1  STOPPED",
            "        CÓD_SALIDA_WIN32   : 3  (0x3)");

        assertEquals(STOPPED, RecoveryMain.extractServiceStateCode(salida));
    }

    @Test
    @DisplayName("estados intermedios de arranque y parada")
    void estadosIntermedios() {
        assertEquals(2, RecoveryMain.extractServiceStateCode("        ESTADO : 2  START_PENDING"));
        assertEquals(3, RecoveryMain.extractServiceStateCode("        ESTADO : 3  STOP_PENDING"));
    }

    @Test
    @DisplayName("un servicio inexistente no devuelve ningun estado")
    void servicioInexistente() {
        String salida = "[SC] EnumQueryServicesStatus:OpenService ERROR 1060:\n\n"
            + "El servicio especificado no existe como servicio instalado.\n";

        assertEquals(NO_STATE, RecoveryMain.extractServiceStateCode(salida));
    }

    @Test
    @DisplayName("salida vacia o nula no devuelve ningun estado")
    void salidaVaciaONula() {
        assertEquals(NO_STATE, RecoveryMain.extractServiceStateCode(null));
        assertEquals(NO_STATE, RecoveryMain.extractServiceStateCode(""));
        assertEquals(NO_STATE, RecoveryMain.extractServiceStateCode("   \n  \n"));
    }

    @Test
    @DisplayName("el nombre del servicio no se confunde con un estado")
    void elNombreNoSeConfundeConUnEstado() {
        assertEquals(NO_STATE, RecoveryMain.extractServiceStateCode("NOMBRE_SERVICIO: TotalPosBridge"));
        assertEquals(NO_STATE, RecoveryMain.extractServiceStateCode("SERVICE_NAME: TotalPosBridge"));
    }
}
