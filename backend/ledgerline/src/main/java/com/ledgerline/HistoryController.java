package com.ledgerline;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class HistoryController {
    private final JdbcTemplate jdbc;

    public HistoryController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Transaction history, newest first. One row per ledger entry on a USER account.
     * Holders only ever see their own accounts; admins and auditors see all user accounts.
     * Other people's account names are never returned to holders.
     */
    @GetMapping("/transactions")
    public List<Map<String, Object>> history(@RequestParam(required = false) Long accountId,
                                             @RequestParam(defaultValue = "100") int limit,
                                             @AuthenticationPrincipal UserPrincipal me) {
        boolean holder = "HOLDER".equals(me.role());
        StringBuilder sql = new StringBuilder("""
            SELECT e.id AS "entryId", e.txn_id AS "receiptId", t.type, e.direction,
                   e.amount_paise AS "amountPaise", e.balance_after_paise AS "balanceAfterPaise", e.ts_ms AS "tsMs",
                   a.id AS "accountId", a.name AS "accountName", u.email AS "ownerEmail",
                   ca.id AS "counterAccountId", ca.type AS "counterType", ca.name AS "counterName",
                   ca.owner_user_id AS "counterOwnerId"
            FROM ledger_entries e
            JOIN transactions t ON t.id = e.txn_id
            JOIN accounts a ON a.id = e.account_id
            LEFT JOIN users u ON u.id = a.owner_user_id
            LEFT JOIN ledger_entries o ON o.txn_id = e.txn_id AND o.account_id <> e.account_id
            LEFT JOIN accounts ca ON ca.id = o.account_id
            WHERE a.type = 'USER'""");
        List<Object> args = new ArrayList<>();
        if (holder) { sql.append(" AND a.owner_user_id = ?"); args.add(me.id()); }
        if (accountId != null) { sql.append(" AND a.id = ?"); args.add(accountId); }
        sql.append(" ORDER BY e.id DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 500)));

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(sql.toString(), args.toArray())) {
            boolean credit = "CREDIT".equals(r.get("direction"));
            Object cid = r.get("counterAccountId");
            String counter;
            if (cid == null) counter = "unknown account";
            else if ("SYSTEM".equals(r.get("counterType"))) counter = "cash";
            else {
                Number owner = (Number) r.get("counterOwnerId");
                boolean mine = owner != null && owner.longValue() == me.id();
                counter = (mine || !holder) ? "#" + cid + " " + r.get("counterName") : "account #" + cid;
            }
            String description = switch ((String) r.get("type")) {
                case "DEPOSIT" -> "Money added";
                case "WITHDRAWAL" -> "Money withdrawn";
                case "TRANSFER" -> credit ? "Received from " + counter : "Sent to " + counter;
                default -> "Reversal " + (credit ? "from " : "to ") + counter;
            };
            Map<String, Object> row = new LinkedHashMap<>();
            for (String k : List.of("entryId", "receiptId", "type", "direction", "amountPaise", "balanceAfterPaise",
                    "tsMs", "accountId", "accountName", "ownerEmail")) row.put(k, r.get(k));
            row.put("description", description);
            out.add(row);
        }
        return out;
    }
}
