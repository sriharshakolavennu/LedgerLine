package com.ledgerline;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuditController {
    private final JdbcTemplate jdbc;

    public AuditController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Any logged-in user: aggregates only, no personal data. One SQL statement = one consistent snapshot. */
    @GetMapping("/audit/conservation")
    public Map<String, Object> conservation() {
        Map<String, Object> r = jdbc.queryForMap(
            "SELECT (SELECT COALESCE(SUM(balance_paise),0) FROM accounts) AS total_balance, "
          + "(SELECT COALESCE(SUM(amount_paise),0) FROM ledger_entries WHERE direction='DEBIT') AS debits, "
          + "(SELECT COALESCE(SUM(amount_paise),0) FROM ledger_entries WHERE direction='CREDIT') AS credits, "
          + "(SELECT COUNT(*) FROM accounts a WHERE a.balance_paise <> COALESCE((SELECT SUM(CASE WHEN e.direction='CREDIT' "
          + "THEN e.amount_paise ELSE -e.amount_paise END) FROM ledger_entries e WHERE e.account_id=a.id),0)) AS mismatched");
        long total = ((Number) r.get("total_balance")).longValue();
        long d = ((Number) r.get("debits")).longValue();
        long c = ((Number) r.get("credits")).longValue();
        long bad = ((Number) r.get("mismatched")).longValue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("balanced", total == 0 && d == c && bad == 0);
        out.put("totalBalancePaise", total);
        out.put("debitsPaise", d);
        out.put("creditsPaise", c);
        out.put("mismatchedAccounts", bad);
        return out;
    }

    @GetMapping("/audit/verify-chain/{accountId}")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public Map<String, Object> verify(@PathVariable long accountId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, txn_id, direction, amount_paise, balance_after_paise, prev_hash, entry_hash, ts_ms "
          + "FROM ledger_entries WHERE account_id=? ORDER BY id", accountId);
        String prev = "GENESIS";
        Long broken = null;
        for (Map<String, Object> r : rows) {
            String calc = recompute(r, accountId);
            if (!prev.equals(r.get("prev_hash")) || !calc.equals(r.get("entry_hash"))) {
                broken = ((Number) r.get("id")).longValue();
                break;
            }
            prev = (String) r.get("entry_hash");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accountId", accountId);
        out.put("entries", rows.size());
        out.put("intact", broken == null);
        out.put("brokenAtEntryId", broken);
        return out;
    }

    @GetMapping("/audit/log")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public List<Map<String, Object>> log() {
        return jdbc.queryForList("SELECT id, actor_user_id AS \"actorUserId\", action, entity, entity_id AS \"entityId\", "
                + "created_at AS \"createdAt\" FROM audit_log ORDER BY id DESC LIMIT 100");
    }

    /** Public proof receipt: exposes only hashes, type, time and integrity status. No names, accounts or amounts. */
    @GetMapping("/public/receipts/{id}")
    public Map<String, Object> receipt(@PathVariable String id) {
        UUID u;
        try { u = UUID.fromString(id); } catch (IllegalArgumentException e) { throw new ApiException(404, "RECEIPT_NOT_FOUND", "Receipt not found"); }
        List<Map<String, Object>> tx = jdbc.queryForList("SELECT type, created_at FROM transactions WHERE id=?", u);
        if (tx.isEmpty()) throw new ApiException(404, "RECEIPT_NOT_FOUND", "Receipt not found");
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, txn_id, account_id, direction, amount_paise, balance_after_paise, prev_hash, entry_hash, ts_ms "
          + "FROM ledger_entries WHERE txn_id=? ORDER BY id", u);
        boolean intact = !rows.isEmpty();
        List<String> hashes = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long acct = ((Number) r.get("account_id")).longValue();
            List<String> p = jdbc.queryForList("SELECT entry_hash FROM ledger_entries WHERE account_id=? AND id<? ORDER BY id DESC LIMIT 1",
                    String.class, acct, r.get("id"));
            String expectedPrev = p.isEmpty() ? "GENESIS" : p.get(0);
            if (!expectedPrev.equals(r.get("prev_hash")) || !recompute(r, acct).equals(r.get("entry_hash"))) intact = false;
            hashes.add((String) r.get("entry_hash"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("receiptId", id);
        out.put("type", tx.get(0).get("type"));
        out.put("createdAt", String.valueOf(tx.get(0).get("created_at")));
        out.put("entryCount", rows.size());
        out.put("intact", intact);
        out.put("entryHashes", hashes);
        return out;
    }

    private String recompute(Map<String, Object> r, long accountId) {
        return Hashing.entryHash((String) r.get("prev_hash"), r.get("txn_id"),
                accountId, (String) r.get("direction"), ((Number) r.get("amount_paise")).longValue(),
                ((Number) r.get("balance_after_paise")).longValue(), ((Number) r.get("ts_ms")).longValue());
    }
}
