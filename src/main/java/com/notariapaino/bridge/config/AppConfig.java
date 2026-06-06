package com.notariapaino.bridge.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Typed configuration loaded once at boot from {@code application.yaml}.
 *
 * <p>The YAML file is resolved in this order:
 * <ol>
 *   <li>Path given by the system property {@code config.path}.</li>
 *   <li>{@code application.yaml} in the working directory (the directory
 *       from which the jar was launched — typically {@code C:\bridge}).</li>
 *   <li>{@code application.yaml} on the classpath (fallback for tests).</li>
 * </ol>
 *
 * <p>All settings are mandatory at boot except the proxy fields. If the
 * file is missing or a required key is absent, the bridge fails fast so
 * the service doesn't silently start with garbage config.
 */
public final class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    public final Server server;
    public final Sdk sdk;
    public final Persistence persistence;

    private AppConfig(Server server, Sdk sdk, Persistence persistence) {
        this.server = server;
        this.sdk = sdk;
        this.persistence = persistence;
    }

    public static AppConfig load() {
        Map<String, Object> root = readYaml();
        Server server = Server.from(asMap(root, "server"));
        Sdk sdk = Sdk.from(asMap(root, "sdk"));
        Persistence persistence = Persistence.from(asMap(root, "persistence"));
        log.info("Configuration loaded: server={}:{}, sdk.afiliacion={}, sdk.pinpad={}",
                server.host, server.port, sdk.afiliacion, sdk.pinpadConexion);
        return new AppConfig(server, sdk, persistence);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readYaml() {
        // 1. system property override
        String prop = System.getProperty("config.path");
        if (prop != null && !prop.isBlank()) {
            return loadFile(Path.of(prop));
        }
        // 2. working dir
        Path local = Path.of("application.yaml");
        if (Files.exists(local)) {
            return loadFile(local);
        }
        // 3. classpath fallback
        try (InputStream in = AppConfig.class.getClassLoader()
                .getResourceAsStream("application.yaml")) {
            if (in == null) {
                throw new IllegalStateException(
                        "application.yaml not found. Looked for: " +
                                "(1) -Dconfig.path, (2) ./application.yaml, " +
                                "(3) classpath:application.yaml");
            }
            log.warn("Loading application.yaml from classpath fallback — " +
                    "this should only happen during tests, not in production");
            return new Yaml().load(in);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read classpath application.yaml", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadFile(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            Map<String, Object> root = new Yaml().load(in);
            log.info("Loaded config from {}", path.toAbsolutePath());
            return root;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + path, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Map<String, Object> parent, String key) {
        Object v = parent.get(key);
        if (v == null) {
            throw new IllegalStateException("Missing config section: " + key);
        }
        if (!(v instanceof Map)) {
            throw new IllegalStateException(
                    "Config section " + key + " must be a map, got " + v.getClass());
        }
        return (Map<String, Object>) v;
    }

    private static String requireString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            throw new IllegalStateException("Missing required config key: " + key);
        }
        return v.toString();
    }

    private static String optString(Map<String, Object> map, String key, String def) {
        Object v = map.get(key);
        return v == null ? def : v.toString();
    }

    private static int requireInt(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            throw new IllegalStateException("Missing required config key: " + key);
        }
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString());
    }

    private static boolean requireBool(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            throw new IllegalStateException("Missing required config key: " + key);
        }
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    // ─── Sub-records ────────────────────────────────────────

    public static final class Server {
        public final String host;
        public final int port;

        private Server(String host, int port) {
            this.host = host;
            this.port = port;
        }

        static Server from(Map<String, Object> m) {
            return new Server(
                    optString(m, "host", "0.0.0.0"),
                    requireInt(m, "port")
            );
        }
    }

    /**
     * SDK configuration that maps 1:1 to the BBVA {@code Configuracion}
     * setters documented on pages 22-24 of the SDK manual. Naming kept
     * close to the SDK's own field names so cross-referencing the manual
     * is unambiguous.
     */
    public static final class Sdk {
        public final String rutaBase;
        public final String pinpadConexion;
        public final String pinpadPuerto;
        public final String pinpadTimeOut;
        public final String pinpadMensaje;
        public final boolean pinpadAndroid;
        public final String hostUrl;
        public final String hostTimeOut;
        public final String afiliacion;
        public final String monedaAfiliacion;
        public final String idAplicacion;
        public final String claveSecreta;
        public final String operador;
        public final String numeroTerminal;
        public final String urlProxy;
        public final String puertoProxy;
        public final boolean logs;

        private Sdk(String rutaBase, String pinpadConexion, String pinpadPuerto,
                    String pinpadTimeOut, String pinpadMensaje, boolean pinpadAndroid,
                    String hostUrl, String hostTimeOut, String afiliacion,
                    String monedaAfiliacion, String idAplicacion, String claveSecreta,
                    String operador, String numeroTerminal,
                    String urlProxy, String puertoProxy, boolean logs) {
            this.rutaBase = rutaBase;
            this.pinpadConexion = pinpadConexion;
            this.pinpadPuerto = pinpadPuerto;
            this.pinpadTimeOut = pinpadTimeOut;
            this.pinpadMensaje = pinpadMensaje;
            this.pinpadAndroid = pinpadAndroid;
            this.hostUrl = hostUrl;
            this.hostTimeOut = hostTimeOut;
            this.afiliacion = afiliacion;
            this.monedaAfiliacion = monedaAfiliacion;
            this.idAplicacion = idAplicacion;
            this.claveSecreta = claveSecreta;
            this.operador = operador;
            this.numeroTerminal = numeroTerminal;
            this.urlProxy = urlProxy;
            this.puertoProxy = puertoProxy;
            this.logs = logs;
        }

        static Sdk from(Map<String, Object> m) {
            return new Sdk(
                    requireString(m, "rutaBase"),
                    requireString(m, "pinpadConexion"),
                    optString(m, "pinpadPuerto", ""),
                    requireString(m, "pinpadTimeOut"),
                    optString(m, "pinpadMensaje", "BBVA"),
                    requireBool(m, "pinpadAndroid"),
                    requireString(m, "hostUrl"),
                    requireString(m, "hostTimeOut"),
                    requireString(m, "afiliacion"),
                    requireString(m, "monedaAfiliacion"),
                    requireString(m, "idAplicacion"),
                    requireString(m, "claveSecreta"),
                    requireString(m, "operador"),
                    optString(m, "numeroTerminal", "1"),
                    optString(m, "urlProxy", ""),
                    optString(m, "puertoProxy", ""),
                    requireBool(m, "logs")
            );
        }
    }

    public static final class Persistence {
        public final String dbFile;

        private Persistence(String dbFile) {
            this.dbFile = dbFile;
        }

        static Persistence from(Map<String, Object> m) {
            return new Persistence(requireString(m, "dbFile"));
        }
    }
}
