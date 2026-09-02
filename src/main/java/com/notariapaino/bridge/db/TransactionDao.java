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

/**
 * SQLite-backed persistence of approved transactions for the current shift.
 *
 * <p>The bridge stores every approved operation (ventas, anulaciones, carga
 * de llaves) here so the kiosk Flutter app can list them via
 * {@code GET /api/transactions/turno-actual} without needing to hit the
 * BBVA host every time the operator opens the anulaciones screen.
 *
 * <p>This is a local cache, NOT the source of truth for accounting —
 * BBVA's host is. We persist a subset of the SDK {@code Respuesta} fields
 * sufficient for the kiosk to display transactions and trigger anulaciones
 * (we need {@code referenciaFinanciera}). The {@code Reporteria} endpoints
 * still query the host for the audit reports.
 *
 * <p>On cierre de turno we clear the table — the SDK + host hold the
 * historical record, the bridge only cares about the live shift.
 *
 * <p>Schema is created on first connection via {@link #initSchema()}; safe
 * to call repeatedly because of {@code IF NOT EXISTS}.
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
                    modo_lectura TEXT,
                    codigo_respuesta TEXT,
                    leyenda TEXT,
                    firma TEXT,
                    serie_terminal TEXT,
                    numero_terminal TEXT,
                    anulada INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL
                )
                """);
            st.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_transactions_referencia " +
                "ON transactions(referencia_financiera)"
            );
            // Migración 2026-09-02: columnas nuevas para poder emitir el
            // voucher con el mismo layout que devuelve el SDK BBVA
            // (Javier pidió salir de la plantilla local y usar los datos
            // tal cual los manda el SDK, incluyendo la razón social real
            // del comercio, el AID EMV, el App Label y el criptograma).
            // SQLite no tiene ADD COLUMN IF NOT EXISTS, así que probamos
            // cada ALTER y absorbemos el error "duplicate column name".
            addColumnIfMissing(st, "razon_social");
            addColumnIfMissing(st, "id_aplicacion_tarjeta");
            addColumnIfMissing(st, "criptograma_tarjeta");
            log.info("SQLite schema ready at {}", jdbcUrl);
        } catch (SQLException e) {
            throw new RuntimeException("Could not initialize SQLite schema", e);
        }
    }

    private static void addColumnIfMissing(Statement st, String col) {
        try {
            st.executeUpdate("ALTER TABLE transactions ADD COLUMN " + col + " TEXT");
            log.info("SQLite: added column transactions.{}", col);
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (!msg.contains("duplicate column")) {
                log.warn("SQLite: could not add column {}: {}", col, e.getMessage());
            }
        }
    }

    /**
     * Persist an approved transaction. {@code tipo} is the operation kind
     * ({@code VENTA}, {@code VENTA_QR}, {@code ANULACION_VENTA_TARJETA},
     * {@code ANULACION_VENTA_QR}). Idempotent on {@code idTransaccion}:
     * a second insert with the same id is silently ignored (the SDK can
     * occasionally hand us the same response twice during retries).
     */
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
                    tarjetahabiente, aplicacion_tarjeta, modo_lectura,
                    codigo_respuesta, leyenda, firma, serie_terminal,
                    numero_terminal, anulada, created_at,
                    razon_social, id_aplicacion_tarjeta, criptograma_tarjeta
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
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
            ps.setString(11, r.getModoLectura());
            ps.setString(12, r.getCodigoRespuesta());
            ps.setString(13, r.getLeyenda());
            ps.setString(14, r.getFirma() == null ? null : r.getFirma().name());
            ps.setString(15, r.getSerieTerminal());
            ps.setString(16, r.getNumeroTerminal());
            ps.setInt(17, 0);
            ps.setLong(18, System.currentTimeMillis());
            ps.setString(19, r.getRazonSocial());
            ps.setString(20, r.getIdAplicacionTarjeta());
            ps.setString(21, r.getCriptogramaTarjeta());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("persist({}, {}) failed: {}", tipo, idTx, e.getMessage());
        }
    }

    /**
     * Mark the original sale identified by {@code referenciaFinanciera} as
     * anulada. Called after a successful anulación so the kiosk Anulaciones
     * screen greys it out and prevents re-anulación.
     */
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

    /** Drop every transaction. Called after a successful cierre de turno. */
    public void clearShift() {
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement st = conn.createStatement()) {
            int n = st.executeUpdate("DELETE FROM transactions");
            log.info("clearShift: removed {} row(s)", n);
        } catch (SQLException e) {
            log.error("clearShift failed: {}", e.getMessage());
        }
    }

    /**
     * Look up a single transaction by its SDK-assigned UUID. Used by the
     * voucher reprint endpoint to rebuild the printable text from cached
     * fields when the kiosk asks to reprint an old sale.
     *
     * @return the row as a flat map, or {@code null} if no match.
     */
    public Map<String, Object> findByIdTransaccion(String idTx) {
        if (idTx == null || idTx.isBlank()) return null;
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM transactions WHERE id_transaccion = ?")) {
            ps.setString(1, idTx);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rowToMap(rs);
                }
            }
        } catch (SQLException e) {
            log.error("findByIdTransaccion({}) failed: {}", idTx, e.getMessage());
        }
        return null;
    }

    /**
     * Return every transaction in the current shift, oldest first. Each row
     * is a flat {@code Map<String,Object>} that Jackson serializes directly
     * into the response body of {@code GET /api/transactions/turno-actual}.
     */
    public List<Map<String, Object>> listCurrentShift() {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM transactions ORDER BY created_at ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rowToMap(rs));
            }
        } catch (SQLException e) {
            log.error("listCurrentShift failed: {}", e.getMessage());
        }
        return out;
    }

    /** Shared row → map conversion used by find/list. */
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
        row.put("modoLectura", rs.getString("modo_lectura"));
        row.put("codigoRespuesta", rs.getString("codigo_respuesta"));
        row.put("leyenda", rs.getString("leyenda"));
        row.put("firma", rs.getString("firma"));
        row.put("serieTerminal", rs.getString("serie_terminal"));
        row.put("numeroTerminal", rs.getString("numero_terminal"));
        row.put("anulada", rs.getInt("anulada") == 1);
        // Campos agregados 2026-09-02 para voucher-desde-SDK. En rows
        // pre-migración vienen null, el formatter los omite si no están.
        row.put("razonSocial", getNullableString(rs, "razon_social"));
        row.put("idAplicacionTarjeta", getNullableString(rs, "id_aplicacion_tarjeta"));
        row.put("criptogramaTarjeta", getNullableString(rs, "criptograma_tarjeta"));
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
