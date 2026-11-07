package com.ledgerline;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Chaos Lab: real concurrent traffic against the real service. Dev profile + ADMIN only. */
@RestController
@RequestMapping("/api/chaos")
@Profile("dev")
public class ChaosController {
    private final MoneyService money;
    private final JdbcTemplate jdbc;
    private final AuditController audit;

    public ChaosController(MoneyService money, JdbcTemplate jdbc, AuditController audit) {
        this.money = money; this.jdbc = jdbc; this.audit = audit;
    }

    @PostMapping("/run/{scenario}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> run(@PathVariable String scenario, @AuthenticationPrincipal UserPrincipal me) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", scenario);
        long uid = me.id();
        switch (scenario) {
            case "overdraw" -> {
                long acct = newAccount(uid, "Chaos overdraw", 50_000);
                Map<String, Integer> o = blast(20, i -> attempt(() -> money.withdraw(uid, "ADMIN", "chaos-" + UUID.randomUUID(), acct, 10_000)));
                long after = balance(acct);
                out.put("expectation", "20 parallel withdrawals of Rs100 from Rs500: exactly 5 succeed, balance ends at Rs0, never negative");
                out.put("beforePaise", 50_000); out.put("afterPaise", after); out.put("outcomes", o);
                out.put("ok", o.getOrDefault("SUCCEEDED", 0) == 5 && after == 0);
            }
            case "duplicate" -> {
                long acct = newAccount(uid, "Chaos duplicate", 0);
                String key = "chaos-dup-" + UUID.randomUUID();
                Map<String, Integer> o = blast(10, i -> attempt(() -> money.deposit(uid, "ADMIN", key, acct, 10_000)));
                long after = balance(acct);
                Integer txns = jdbc.queryForObject("SELECT COUNT(DISTINCT txn_id) FROM ledger_entries WHERE account_id=?", Integer.class, acct);
                out.put("expectation", "Same Idempotency-Key sent 10 times at once: exactly 1 transaction, 9 replays");
                out.put("beforePaise", 0); out.put("afterPaise", after); out.put("outcomes", o); out.put("transactionsCreated", txns);
                out.put("ok", txns == 1 && after == 10_000);
            }
            case "storm" -> {
                long[] accts = new long[5];
                for (int i = 0; i < 5; i++) accts[i] = newAccount(uid, "Chaos storm " + (i + 1), 100_000);
                Random rnd = new Random();
                Map<String, Integer> o = blast(50, i -> {
                    int a = rnd.nextInt(5), b = (a + 1 + rnd.nextInt(4)) % 5;
                    long amt = 1 + rnd.nextInt(30_000);
                    return attempt(() -> money.transfer(uid, "ADMIN", "chaos-" + UUID.randomUUID(), accts[a], accts[b], amt));
                });
                long after = 0;
                for (long a : accts) after += balance(a);
                out.put("expectation", "50 random parallel transfers between 5 accounts: total money unchanged, no account negative");
                out.put("beforePaise", 500_000); out.put("afterPaise", after); out.put("outcomes", o);
                out.put("ok", after == 500_000);
            }
            default -> throw new ApiException(404, "UNKNOWN_SCENARIO", "Unknown scenario");
        }
        out.put("conservation", audit.conservation());
        return out;
    }

    private long newAccount(long uid, String name, long fundPaise) {
        long id = jdbc.queryForObject("INSERT INTO accounts(owner_user_id,name,type) VALUES (?,?,'USER') RETURNING id", Long.class, uid, name);
        if (fundPaise > 0) money.deposit(uid, "ADMIN", "chaos-fund-" + UUID.randomUUID(), id, fundPaise);
        return id;
    }

    private long balance(long acct) {
        return jdbc.queryForObject("SELECT balance_paise FROM accounts WHERE id=?", Long.class, acct);
    }

    private String attempt(Callable<Map<String, Object>> c) {
        try {
            return Boolean.TRUE.equals(c.call().get("replayed")) ? "REPLAYED" : "SUCCEEDED";
        } catch (ApiException e) {
            return "REJECTED_" + e.code;
        } catch (Exception e) {
            return "ERROR";
        }
    }

    private Map<String, Integer> blast(int n, IntFunction<String> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(n, 20));
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            fs.add(pool.submit(() -> { go.await(); return task.apply(idx); }));
        }
        go.countDown();
        Map<String, Integer> tally = new TreeMap<>();
        for (Future<String> f : fs) tally.merge(f.get(30, TimeUnit.SECONDS), 1, Integer::sum);
        pool.shutdown();
        return tally;
    }
}
