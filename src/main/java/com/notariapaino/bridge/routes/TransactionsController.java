package com.notariapaino.bridge.routes;

import com.notariapaino.bridge.db.TransactionDao;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local persistence read endpoint: {@code GET /api/transactions/turno-actual}.
 *
 * <p>Returns every approved operation persisted by the bridge for the current
 * shift. Used by the kiosk's Anulaciones screen to list voidable sales without
 * pestering the host every time.
 *
 * <p>Cleared on a successful {@code POST /api/cierre-turno} so each shift
 * starts fresh.
 */
public final class TransactionsController {

    private static final Logger log = LoggerFactory.getLogger(TransactionsController.class);

    private final TransactionDao dao;

    public TransactionsController(TransactionDao dao) {
        this.dao = dao;
    }

    public Handler currentShift() {
        return new Handler() {
            @Override
            public void handle(@NotNull Context ctx) {
                List<Map<String, Object>> txs = dao.listCurrentShift();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", true);
                body.put("count", txs.size());
                body.put("transactions", txs);
                log.debug("transactions/turno-actual returning {} rows", txs.size());
                ctx.status(200).json(body);
            }
        };
    }
}
