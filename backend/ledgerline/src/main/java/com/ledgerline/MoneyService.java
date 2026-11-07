package com.ledgerline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * All money movement goes through here. Every public method runs in ONE database transaction:
 * idempotency check -> row locks (ascending id) -> balanced double-entry -> hash chain -> audit.
 */
@Service
public class MoneyService {
    public static final long MAX_PAISE = 1_000_000_000L; // 1 crore rupees per operation

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;

    public MoneyService(JdbcTemplate jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    record Leg(long accountId, String dir, long amount) {}

    @Transactional
    public Map<String, Object> deposit(long uid, String role, String key, long accountId, long amount) {
        validate(amount);
        return idem(uid, key, "DEPOSIT:" + accountId + ":" + amount, () -> {
            requireUserAccess(uid, role, accountId);
            return post("DEPOSIT", uid, null, List.of(
                    new Leg(accountId, "CREDIT", amount),
                    new Leg(systemId("CASH_IN_CLEARING"), "DEBIT", amount)));
        });
    }

    @Transactional
    public Map<String, Object> withdraw(long uid, String role, String key, long accountId, long amount) {
        validate(amount);
        return idem(uid, key, "WITHDRAWAL:" + accountId + ":" + amount, () -> {
            requireUserAccess(uid, role, accountId);
            return post("WITHDRAWAL", uid, null, List.of(
                    new Leg(accountId, "DEBIT", amount),
                    new Leg(systemId("CASH_OUT_CLEARING"), "CREDIT", amount)));
        });
    }

    @Transactional
    public Map<String, Object> transfer(long uid, String role, String key, long from, long to, long amount) {
        validate(amount);
        if (from == to) throw new ApiException(400, "SAME_ACCOUNT", "Cannot transfer to the same account");
        return idem(uid, key, "TRANSFER:" + from + ":" + to + ":" + amount, () -> {
            requireUserAccess(uid, role, from);
            if (jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE id=? AND type='USER'", Integer.class, to) == 0)
                throw ApiException.notFound();
            return post("TRANSFER", uid, null, List.of(
                    new Leg(from, "DEBIT", amount), new Leg(to, "CREDIT", amount)));
        });
    }

    @Transactional
    public Map<String, Object> reverse(long uid, String key, UUID txnId) {
        return idem(uid, key, "REVERSE:" + txnId, () -> {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE reverses_txn_id=?", Integer.class, txnId) > 0)
                throw new ApiException(409, "ALREADY_REVERSED", "Transaction already reversed");
            List<Map<String, Object>> es = jdbc.queryForList(
                    "SELECT account_id, direction, amount_paise FROM ledger_entries WHERE txn_id=? ORDER BY id", txnId);
            if (es.isEmpty()) throw new ApiException(404, "TXN_NOT_FOUND", "Transaction not found");
            List<Leg> legs = new ArrayList<>();
            for (Map<String, Object> e : es) {
                String flipped = "DEBIT".equals(e.get("direction")) ? "CREDIT" : "DEBIT";
                legs.add(new Leg(((Number) e.get("account_id")).longValue(), flipped, ((Number) e.get("amount_paise")).longValue()));
            }
            return post("REVERSAL", uid, txnId, legs);
        });
    }

    // ---------- internals ----------

    private void validate(long amount) {
        if (amount <= 0 || amount > MAX_PAISE)
            throw new ApiException(400, "INVALID_AMOUNT", "Amount must be between \u20B90.01 and \u20B910,000,000.00");
    }

    private long systemId(String name) {
        return jdbc.queryForObject("SELECT id FROM accounts WHERE type='SYSTEM' AND name=?", Long.class, name);
    }

    /** Admin may use any USER account; holders only their own. Others get 404 (no existence leak). */
    private void requireUserAccess(long uid, String role, long accountId) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT owner_user_id, type FROM accounts WHERE id=?", accountId);
        if (r.isEmpty() || !"USER".equals(r.get(0).get("type"))) throw ApiException.notFound();
        if ("ADMIN".equals(role)) return;
        Object owner = r.get(0).get("owner_user_id");
        if (owner == null || ((Number) owner).longValue() != uid) throw ApiException.notFound();
    }

    /**
     * Idempotency inside the same DB transaction: the key row is inserted first. A concurrent identical request
     * blocks on the unique index until we commit, then replays our stored response.
     */
    private Map<String, Object> idem(long uid, String key, String fingerprint, Supplier<Map<String, Object>> work) {
        if (key == null || key.isBlank() || key.length() > 100)
            throw new ApiException(400, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header is required");
        String h = Hashing.sha256Hex(fingerprint);
        int inserted = jdbc.update(
                "INSERT INTO idempotency_keys(user_id, idem_key, request_hash) VALUES (?,?,?) ON CONFLICT DO NOTHING", uid, key, h);
        if (inserted == 0) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT request_hash, response_body FROM idempotency_keys WHERE user_id=? AND idem_key=?", uid, key);
            if (!h.equals(row.get("request_hash")))
                throw new ApiException(422, "IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was used with a different request");
            Map<String, Object> body = read((String) row.get("response_body"));
            body.put("replayed", true);
            return body;
        }
        Map<String, Object> result = work.get();
        jdbc.update("UPDATE idempotency_keys SET response_body=? WHERE user_id=? AND idem_key=?", write(result), uid, key);
        return result;
    }

    private Map<String, Object> post(String type, long uid, UUID reverses, List<Leg> legs) {
        long debit = legs.stream().filter(l -> l.dir().equals("DEBIT")).mapToLong(Leg::amount).sum();
        long credit = legs.stream().filter(l -> l.dir().equals("CREDIT")).mapToLong(Leg::amount).sum();
        if (debit != credit) throw new IllegalStateException("Unbalanced transaction");

        UUID txn = UUID.randomUUID();
        jdbc.update("INSERT INTO transactions(id, type, initiated_by, reverses_txn_id) VALUES (?,?,?,?)", txn, type, uid, reverses);

        // Lock in ascending id order -> no deadlocks between opposite transfers
        List<Long> ids = legs.stream().map(Leg::accountId).distinct().sorted().toList();
        String in = ids.stream().map(i -> "?").collect(Collectors.joining(","));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, type, balance_paise FROM accounts WHERE id IN (" + in + ") ORDER BY id FOR UPDATE", ids.toArray());
        if (rows.size() != ids.size()) throw ApiException.notFound();

        Map<Long, Long> bal = new HashMap<>();
        Map<Long, String> typ = new HashMap<>();
        for (Map<String, Object> r : rows) {
            long id = ((Number) r.get("id")).longValue();
            bal.put(id, ((Number) r.get("balance_paise")).longValue());
            typ.put(id, (String) r.get("type"));
        }

        long ts = System.currentTimeMillis();
        for (Leg l : legs) {
            long b = bal.get(l.accountId()) + ("CREDIT".equals(l.dir()) ? l.amount() : -l.amount());
            if (b < 0 && "USER".equals(typ.get(l.accountId())))
                throw new ApiException(409, "INSUFFICIENT_FUNDS", "Insufficient funds");
            bal.put(l.accountId(), b);
            List<String> p = jdbc.queryForList(
                    "SELECT entry_hash FROM ledger_entries WHERE account_id=? ORDER BY id DESC LIMIT 1", String.class, l.accountId());
            String prev = p.isEmpty() ? "GENESIS" : p.get(0);
            String eh = Hashing.entryHash(prev, txn, l.accountId(), l.dir(), l.amount(), b, ts);
            jdbc.update("INSERT INTO ledger_entries(txn_id, account_id, direction, amount_paise, balance_after_paise, prev_hash, entry_hash, ts_ms) VALUES (?,?,?,?,?,?,?,?)",
                    txn, l.accountId(), l.dir(), l.amount(), b, prev, eh, ts);
        }
        for (Long id : ids) jdbc.update("UPDATE accounts SET balance_paise=? WHERE id=?", bal.get(id), id);

        jdbc.update("INSERT INTO audit_log(actor_user_id, action, entity, entity_id, detail) VALUES (?,?,?,?,?)",
                uid, type, "TRANSACTION", txn.toString(), legs.toString());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("receiptId", txn.toString());
        out.put("type", type);
        out.put("status", "POSTED");
        out.put("accountId", legs.get(0).accountId());
        out.put("balanceAfterPaise", bal.get(legs.get(0).accountId()));
        out.put("replayed", false);
        return out;
    }

    private String write(Object o) {
        try { return om.writeValueAsString(o); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private Map<String, Object> read(String s) {
        try { return om.readValue(s, new TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
