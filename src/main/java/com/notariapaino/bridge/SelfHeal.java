package com.notariapaino.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.concurrent.Callable;

/**
 * Phase-0 boot-time self-healing for the TotalPOS Bridge.
 *
 * <p>Motivation: the mini-PC hosting the bridge occasionally suffers abrupt
 * power loss (UPS not present, unplugged, breaker tripped). When Windows
 * comes back and NSSM auto-starts the bridge, a variety of stale state
 * from the previous run can prevent {@code Main} from booting:
 *
 * <ul>
 *   <li>SQLite {@code -journal} / {@code -wal} / {@code -shm} files left
 *       inconsistent — opening the DB throws {@code SQLITE_CORRUPT} or
 *       {@code SQLITE_BUSY}.</li>
 *   <li>The BBVA SDK's working directory (bines cache, downloaded config,
 *       reversos pending) may contain half-written files that make
 *       {@code Interfaz.inicializar()} throw.</li>
 *   <li>Windows can hold zombie file handles on log files for several
 *       seconds after a reset, causing SLF4J's file appender to fail.</li>
 * </ul>
 *
 * <p>Without a self-heal step, the JVM exits non-zero and NSSM restarts it
 * every 5s in a loop that never converges — the bridge stays down until
 * someone SSH/RDPs in, which defeats the "no manual intervention" goal.
 *
 * <p>This class runs BEFORE {@link com.notariapaino.bridge.db.TransactionDao#initSchema()}
 * and {@link SdkManager#init()} in {@link Main} and handles the recoverable
 * cases. It is intentionally conservative: it never touches files it can't
 * confidently classify (e.g. it never deletes anything under the SDK's
 * {@code rutaBase} — that's the SDK's own state and we don't own the format).
 *
 * <p>All operations log both success and failure, so the operator has a
 * paper trail in {@code service.out.log} explaining what was quarantined
 * or reset. Nothing here throws — a failure of self-heal degrades to the
 * old behavior (whatever tries next will fail on its own and NSSM will
 * throttle-restart per the new {@code AppThrottle} setting).
 */
public final class SelfHeal {

    private static final Logger log = LoggerFactory.getLogger(SelfHeal.class);

    private SelfHeal() {}

    /**
     * Check SQLite integrity and, if the DB is unreadable/corrupt, move it
     * (and its journals) aside so a fresh schema can be created. If the DB
     * opens cleanly, sweep any stale {@code -journal}/{@code -wal}/{@code -shm}
     * files that the SDK's writer may have left behind but that SQLite has
     * already fully absorbed on the successful {@code integrity_check} query.
     *
     * <p>Impact on data: the bridge's local SQLite cache is the source only
     * for the current shift's transaction list (used by the anulaciones and
     * reimprimir voucher screens). The authoritative source is BBVA's host
     * — reports and reprints of prior shifts hit the host, not this cache.
     * So quarantining a corrupt DB loses at most the CURRENT shift's local
     * list, not accounting data.
     *
     * <p>Safe to call when the DB file doesn't exist yet — first boot skips
     * this step.
     */
    public static void healSqlite(String dbFile) {
        Path db = Path.of(dbFile);
        if (!Files.exists(db)) {
            log.info("SelfHeal: SQLite DB '{}' does not exist yet — first boot, nothing to check", dbFile);
            return;
        }

        String jdbcUrl = "jdbc:sqlite:" + dbFile;
        String result;
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
            result = rs.next() ? rs.getString(1) : "unknown";
        } catch (SQLException e) {
            log.error("SelfHeal: cannot open SQLite '{}' for integrity_check ({}) — quarantining",
                    dbFile, e.getMessage());
            quarantineDbFamily(dbFile);
            return;
        }

        if ("ok".equalsIgnoreCase(result)) {
            log.info("SelfHeal: SQLite integrity_check = ok");
            // Journals are legitimately created during a live write and
            // SQLite absorbs them into the main DB on a clean open. Any
            // that remain after the successful open+query above are stale
            // leftovers from the abrupt shutdown; deleting them prevents
            // subsequent writers from being confused by them.
            deleteIfExists(dbFile + "-journal");
            deleteIfExists(dbFile + "-wal");
            deleteIfExists(dbFile + "-shm");
            return;
        }

        log.error("SelfHeal: SQLite integrity_check FAILED ('{}') — quarantining", result);
        quarantineDbFamily(dbFile);
    }

    /**
     * Move the SQLite DB and its journal family aside under
     * {@code <name>.corrupt-<timestamp>} so the next open creates a fresh
     * DB via {@code CREATE TABLE IF NOT EXISTS}. Preserves the bad DB on
     * disk in case Javier / soporte want to forensically recover it.
     */
    private static void quarantineDbFamily(String dbFile) {
        String suffix = ".corrupt-" + Instant.now().toString().replace(':', '-');
        quarantine(dbFile, suffix);
        quarantine(dbFile + "-journal", suffix);
        quarantine(dbFile + "-wal", suffix);
        quarantine(dbFile + "-shm", suffix);
    }

    private static void quarantine(String path, String suffix) {
        Path src = Path.of(path);
        if (!Files.exists(src)) {
            return;
        }
        Path target = Path.of(path + suffix);
        try {
            Files.move(src, target, StandardCopyOption.REPLACE_EXISTING);
            log.warn("SelfHeal: quarantined {} → {}", src.getFileName(), target.getFileName());
        } catch (IOException e) {
            log.error("SelfHeal: could not quarantine {}: {}", src, e.getMessage());
        }
    }

    private static void deleteIfExists(String path) {
        Path p = Path.of(path);
        if (!Files.exists(p)) {
            return;
        }
        try {
            Files.delete(p);
            log.info("SelfHeal: deleted stale journal {}", p.getFileName());
        } catch (IOException e) {
            log.warn("SelfHeal: could not delete stale {}: {}", p, e.getMessage());
        }
    }

    /**
     * Retry a step with linear backoff. Used to absorb Windows zombie file
     * handles (log appender open, DB open) and transient PinPad/USB
     * enumeration races right after boot.
     *
     * <p>Throws the LAST exception on final failure — the caller decides
     * whether to propagate (which exits the process and NSSM restarts us).
     */
    public static <T> T retry(String label, int attempts, long backoffMs, Callable<T> task) throws Exception {
        Exception last = null;
        for (int i = 1; i <= attempts; i++) {
            try {
                T out = task.call();
                if (i > 1) {
                    log.info("SelfHeal.retry '{}' succeeded on attempt {}/{}", label, i, attempts);
                }
                return out;
            } catch (Exception e) {
                last = e;
                log.warn("SelfHeal.retry '{}' attempt {}/{} failed: {}", label, i, attempts, e.getMessage());
                if (i < attempts) {
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw ie;
                    }
                }
            }
        }
        throw last;
    }
}
