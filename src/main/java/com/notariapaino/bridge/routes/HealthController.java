package com.notariapaino.bridge.routes;

import com.notariapaino.bridge.SdkManager;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * Simple liveness/readiness endpoint used by the kiosk to verify that the
 * bridge is up and the SDK was initialized.
 *
 * <p>Returns 200 when both the JVM is alive AND the SDK reports initialized.
 * Returns 503 when the JVM is up but the SDK init failed (e.g. PinPad
 * unreachable at boot, host unreachable, keys missing). The body always
 * contains diagnostic detail so the operator/admin sees WHY.
 */
public final class HealthController {

    private final SdkManager sdk;
    private final long startedAtMillis;
    private final String bridgeVersion;

    public HealthController(SdkManager sdk, String bridgeVersion) {
        this.sdk = sdk;
        this.startedAtMillis = System.currentTimeMillis();
        this.bridgeVersion = bridgeVersion;
    }

    public Handler get() {
        return new Handler() {
            @Override
            public void handle(@NotNull Context ctx) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("status", sdk.isInitialized() ? "OK" : "DEGRADED");
                body.put("bridgeVersion", bridgeVersion);
                body.put("sdkInitialized", sdk.isInitialized());
                body.put("uptimeMillis", System.currentTimeMillis() - startedAtMillis);
                body.put("jvmName", ManagementFactory.getRuntimeMXBean().getVmName());
                body.put("jvmVersion", System.getProperty("java.version"));
                body.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
                ctx.status(sdk.isInitialized() ? 200 : 503).json(body);
            }
        };
    }
}
