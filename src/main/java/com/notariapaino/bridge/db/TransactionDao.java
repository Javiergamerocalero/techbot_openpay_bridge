package com.notariapaino.bridge.db;

import com.eglobal.totalpos.sdk.layout.Respuesta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * SQLite-backed persistence of approved transactions for the current shift.
 *
 * <p>The bridge stores every approved operation (ventas, anulaciones, carga
 * de llaves) here so the kiosk can list them without querying the BBVA host.
 * The host remains the source of truth for accounting.
 */
public final class TransactionDao {

    private static final Logger log = LoggerFactory.getLogger(TransactionDao.class);

    private final String jdbcUrl;

    public TransactionDao(String dbFile) {
        File parent = new File(dbFile).getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            log.warn("Could not create parent dir for SQLite db: {}", parent);
        }
        this.jdbcUrl = "jdbc:sqlite:" + dbFile;
    }

    public void initSchema() {
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement st = conn.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS transactions (
                    id_transaccion TEXT PRIMARY KEY,
                    tipo TEXT NOT NULL,
                    fecha_hora TEXT,
                    autorizacion TEXT,
                    referencia_financiera TEXT,
                    importe TEXT,
                    moneda TEXT,
                    numero_tarjeta TEXT,
                    tarjetahabiente TEXT,
                    aplicacion_tarjeta TEXT,
                    id_aplicacion_tarjeta TEXT,
                    criptograma_tarjeta TEXT,
                    modo_lectura TEXT,
                    codigo_respuesta TEXT,
                    leyenda TEXT,
                    firma TEXT,
                    serie_terminal TEXT,
                    numero_terminal TEXT,
                    razon_social TEXT,
                    direccion TEXT,
                    cuotas TEXT,
                    codigo_promocion TEXT,
                    monto_cuota TEXT,
                    mensajes_promocion TEXT,
                    anulada INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL
                )
                """);
            st.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_transactions_referencia " +
                "ON transactions(referencia_financiera)"
            );

            // Idempotent migrations for databases created by older Bridge releases.
            addColumnIfMissing(st, "id_aplicacion_tarjeta", "TEXT");
            addColumnIfMissing(st, "criptograma_tarjeta", "TEXT");
            addColumnIfMissing(st, "razon_social", "TEXT");
            addColumnIfMissing(st, "direccion", "TEXT");
            addColumnIfMissing(st, "cuotas", "TEXT");
            addColumnIfMissing(st, "codigo_promocion", "TEXT");
            addColumnIfMissing(st, "monto_cuota", "TEXT");
            addColumnIfMissing(st, "mensajes_promocion", "TEXT");
            log.info("SQLite schema ready at {}", jdbcUrl);
        } catch (SQLException e) {
            throw new RuntimeException("Could not initialize SQLite schema", e);
        }
    }

    private static void addColumnIfMissing(Statement st, String col, String type) {
        try {
            st.executeUpdate("ALTER TABLE transactions ADD COLUMN " + col + " " + type);
            log.info("SQLite: added column transactions.{}", col);
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (!msg.contains("duplicate column")) {
                log.warn("SQLite: could not add column {}: {}", col, e.getMessage());
            }
        }
    }

    /** Persist an approved transaction. Idempotent on idTransaccion. */
    public void persist(String tipo, Respuesta r) {
        String idTx = r.getIdTransaccion();
        if (idTx == null || idTx.isBlank()) {
            log.warn("persist({}): respuesta has no idTransaccion, skipping", tipo);
            return;
        }
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement("""
                INSERT OR IGNORE INTO transactions (
                    id_transaccion, tipo, fecha_hora, autorizacion,
                    referencia_financiera, importe, moneda, numero_tarjeta,
                    tarjetahabiente, aplicacion_tarjeta, id_aplicacion_tarjeta,
                    criptograma_tarjeta, modo_lectura, codigo_respuesta,
                    leyenda, firma, serie_terminal, numero_terminal,
                    razon_social, direccion, cuotas, codigo_promocion,
                    monto_cuota, mensajes_promocion, anulada, created_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            ps.setString(1, idTx);
            ps.setString(2, tipo);
            ps.setString(3, r.getFechaHora());
            ps.setString(4, r.getAutorizacion());
            ps.setString(5, r.getReferenciaFinanciera());
            ps.setString(6, r.getImporte());
            ps.setString(7, r.getMoneda() == null ? null : r.getMoneda().name());
            ps.setString(8, r.getNumeroTarjeta());
            ps.setString(9, r.getTarjetahabiente());
            ps.setString(10, r.getAplicacionTarjeta());
            ps.setString(11, r.getIdAplicacionTarjeta());
            ps.setString(12, r.getCriptogramaTarjeta());
            ps.setString(13, r.getModoLectura());
            ps.setString(14, r.getCodigoRespuesta());
            ps.setString(15, r.getLeyenda());
            ps.setString(16, r.getFirma() == null ? null : r.getFirma().name());
            ps.setString(17, r.getSerieTerminal());
            ps.setString(18, r.getNumeroTerminal());
            ps.setString(19, r.getRazonSocial());
            ps.setString(20, r.getDireccion());
            ps.setString(21, r.getCuotas());
            ps.setString(22, r.getCodigoPromocion() == null ? null : r.getCodigoPromocion().name());
            ps.setString(23, r.getMontoCuota());
            ps.setString(24, joinMensajes(r.getMensajes()));
            ps.setInt(25, 0);
            ps.setLong(26, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("persist({}, {}) failed: {}", tipo, idTx, e.getMessage());
        }
    }

    private static String joinMensajes(String[] mensajes) {
        if (mensajes == null || mensajes.length == 0) return null;
        String joined = Stream.of(mensajes)
                .filter(m -> m != null && !m.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(" | "));
        return joined.isBlank() ? null : joined;
    }

    public void markAnuladaByReferencia(String referenciaFinanciera) {
        if (referenciaFinanciera == null || referenciaFinanciera.isBlank()) return;
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE transactions SET anulada = 1 WHERE referencia_financiera = ?")) {
            ps.setString(1, referenciaFinanciera);
            int n = ps.executeUpdate();
            log.info("markAnulada(ref={}) updated {} row(s)", referenciaFinanciera, n);
        } catch (SQLException e) {
            log.error("markAnulada failed: {}", e.getMessage());
        }
    }

    public void clearShift() {
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement st = conn.createStatement()) {
            int n = st.executeUpdate("DELETE FROM transactions");
            log.info("clearShift: removed {} row(s)", n);
        } catch (SQLException e) {
            log.error("clearShift failed: {}", e.getMessage());
        }
    }

    public Map<String, Object> findByIdTransaccion(String idTx) {
        if (idTx == null || idTx.isBlank()) return null;
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM transactions WHERE id_transaccion = ?")) {
            ps.setString(1, idTx);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rowToMap(rs);
            }
        } catch (SQLException e) {
            log.error("findByIdTransaccion({}) failed: {}", idTx, e.getMessage());
        }
        return null;
    }

    public List<Map<String, Object>> listCurrentShift() {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM transactions ORDER BY created_at ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rowToMap(rs));
        } catch (SQLException e) {
            log.error("listCurrentShift failed: {}", e.getMessage());
        }
        return out;
    }

    private static Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("idTransaccion", rs.getString("id_transaccion"));
        row.put("tipo", rs.getString("tipo"));
        row.put("fechaHora", rs.getString("fecha_hora"));
        row.put("autorizacion", rs.getString("autorizacion"));
        row.put("referenciaFinanciera", rs.getString("referencia_financiera"));
        row.put("importe", rs.getString("importe"));
        row.put("moneda", rs.getString("moneda"));
        row.put("numeroTarjeta", rs.getString("numero_tarjeta"));
        row.put("tarjetahabiente", rs.getString("tarjetahabiente"));
        row.put("aplicacionTarjeta", rs.getString("aplicacion_tarjeta"));
        row.put("idAplicacionTarjeta", getNullableString(rs, "id_aplicacion_tarjeta"));
        row.put("criptogramaTarjeta", getNullableString(rs, "criptograma_tarjeta"));
        row.put("modoLectura", rs.getString("modo_lectura"));
        row.put("codigoRespuesta", rs.getString("codigo_respuesta"));
        row.put("leyenda", rs.getString("leyenda"));
        row.put("firma", rs.getString("firma"));
        row.put("serieTerminal", rs.getString("serie_terminal"));
        row.put("numeroTerminal", rs.getString("numero_terminal"));
        row.put("razonSocial", getNullableString(rs, "razon_social"));
        row.put("direccion", getNullableString(rs, "direccion"));
        row.put("cuotas", getNullableString(rs, "cuotas"));
        row.put("codigoPromocion", getNullableString(rs, "codigo_promocion"));
        row.put("montoCuota", getNullableString(rs, "monto_cuota"));
        row.put("mensajesPromocion", getNullableString(rs, "mensajes_promocion"));
        row.put("anulada", rs.getInt("anulada") == 1);
        return row;
    }

    private static String getNullableString(ResultSet rs, String col) {
        try {
            return rs.getString(col);
        } catch (SQLException e) {
            return null;
        }
    }
}
