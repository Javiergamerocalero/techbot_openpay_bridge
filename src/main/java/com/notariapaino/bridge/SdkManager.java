package com.notariapaino.bridge;

import com.eglobal.totalpos.sdk.authorizer.Interfaz;
import com.eglobal.totalpos.sdk.catalog.MONEDA;
import com.eglobal.totalpos.sdk.exception.PeticionException;
import com.eglobal.totalpos.sdk.util.Configuracion;
import com.notariapaino.bridge.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the lifecycle of the TotalPOS SDK inside the bridge process.
 *
 * <p>The SDK exposes a process-wide singleton via {@code Interfaz.getInstance()}.
 * We initialize it once at startup with values from {@link AppConfig} and keep
 * it alive for the lifetime of the JVM. All route controllers call into the
 * SDK through this manager so the init contract (must happen before any
 * {@code Peticion.autorizar()}) is enforced in one place.
 *
 * <p>Idempotent: calling {@link #init()} more than once is a no-op after the
 * first successful initialization. The SDK's own {@code setConfiguracion}
 * tolerates being called repeatedly (it logs "Detectada nueva configuración"
 * and rewires), but our intent is a single boot-time init.
 */
public final class SdkManager {

    private static final Logger log = LoggerFactory.getLogger(SdkManager.class);

    private final AppConfig cfg;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public SdkManager(AppConfig cfg) {
        this.cfg = cfg;
    }

    /**
     * One-shot SDK initialization. Builds the {@link Configuracion} from
     * {@link AppConfig.Sdk}, applies it via {@code Interfaz.setConfiguracion},
     * and calls {@code Interfaz.inicializar()} which kicks off the reverso
     * sync, bines/configuracion downloads and PinPad sync.
     *
     * <p>Throws if the SDK itself rejects the config or the PinPad / host
     * are unreachable at boot. We let the exception propagate so the
     * process exits non-zero and NSSM restarts us — better than starting
     * in a half-initialized state and serving HTTP 500s forever.
     */
    public void init() {
        if (!initialized.compareAndSet(false, true)) {
            log.warn("SdkManager.init() called twice — ignoring");
            return;
        }

        log.info("Initializing TotalPOS SDK…");
        log.info("  rutaBase       = {}", cfg.sdk.rutaBase);
        log.info("  hostUrl        = {}", cfg.sdk.hostUrl);
        log.info("  afiliacion     = {} ({})", cfg.sdk.afiliacion, cfg.sdk.monedaAfiliacion);
        log.info("  pinpad         = {}:{}", cfg.sdk.pinpadConexion, cfg.sdk.pinpadPuerto);
        log.info("  pinpadAndroid  = {}", cfg.sdk.pinpadAndroid);
        log.info("  logs enabled   = {}", cfg.sdk.logs);

        // Ensure rutaBase exists — `Configuracion.setRutaBase` validates it
        // exists and is a directory, throwing PeticionException otherwise.
        File rutaBaseDir = new File(cfg.sdk.rutaBase);
        if (!rutaBaseDir.exists() && !rutaBaseDir.mkdirs()) {
            throw new IllegalStateException(
                    "Could not create rutaBase: " + cfg.sdk.rutaBase);
        }

        // The Configuracion() constructor itself declares `throws
        // PeticionException` even though it doesn't currently throw — keep
        // the instantiation inside the try-catch so we never have an
        // unhandled checked exception path.
        try {
            Configuracion configuracion = new Configuracion();
            configuracion.setLogs(cfg.sdk.logs);
            configuracion.setPinpadAndroid(cfg.sdk.pinpadAndroid);
            configuracion.setPinpadConexion(cfg.sdk.pinpadConexion);
            if (cfg.sdk.pinpadPuerto != null && !cfg.sdk.pinpadPuerto.isBlank()) {
                configuracion.setPinpadPuerto(cfg.sdk.pinpadPuerto);
            }
            configuracion.setPinpadTimeOut(cfg.sdk.pinpadTimeOut);
            configuracion.setPinpadMensaje(cfg.sdk.pinpadMensaje);

            configuracion.setRutaBase(cfg.sdk.rutaBase);

            configuracion.setHostUrl(cfg.sdk.hostUrl);
            configuracion.setHostTimeOut(cfg.sdk.hostTimeOut);

            configuracion.setAfiliacion(cfg.sdk.afiliacion);
            configuracion.setMonedaAfiliacion(MONEDA.valueOf(cfg.sdk.monedaAfiliacion));

            configuracion.setIdAplicacion(cfg.sdk.idAplicacion);
            configuracion.setClaveSecreta(cfg.sdk.claveSecreta);

            // Required by the SDK's validateConfig (not documented in the
            // manual's parameter list but rejected at init if missing).
            // Typical value is "1" for single-terminal installs.
            configuracion.setNumeroTerminal(cfg.sdk.numeroTerminal);

            // Optional proxy.
            if (cfg.sdk.urlProxy != null && !cfg.sdk.urlProxy.isBlank()) {
                configuracion.setUrlProxy(cfg.sdk.urlProxy);
            }
            if (cfg.sdk.puertoProxy != null && !cfg.sdk.puertoProxy.isBlank()) {
                configuracion.setPuertoProxy(cfg.sdk.puertoProxy);
            }

            Interfaz.getInstance().setConfiguracion(configuracion);
            Interfaz.getInstance().inicializar();
            log.info("TotalPOS SDK initialized successfully");
        } catch (PeticionException e) {
            // PinPadKeysException ("Requiere carga de llaves") is THROWN at
            // init when keys aren't loaded yet — but that's not fatal,
            // operations like /api/carga-llaves still work and the operator
            // can fix it. Log + continue.
            if (e.getClass().getSimpleName().equals("PinPadKeysException")) {
                log.warn("SDK initialized but PinPad requires key loading. " +
                        "Call POST /api/carga-llaves to provision.");
            } else {
                initialized.set(false);
                throw new RuntimeException("SDK initialization failed: " + e.getMessage(), e);
            }
        } catch (Exception e) {
            initialized.set(false);
            throw new RuntimeException("SDK initialization failed: " + e.getMessage(), e);
        }
    }

    /**
     * Configured operador code injected into every {@code Peticion} the
     * route controllers build.
     */
    public String operador() {
        return cfg.sdk.operador;
    }

    /**
     * Whether {@link #init} completed successfully. Health and
     * pre-operation guards consult this.
     */
    public boolean isInitialized() {
        return initialized.get();
    }
}
