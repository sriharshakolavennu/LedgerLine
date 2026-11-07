package com.ledgerline;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Runs against the local PostgreSQL configured in application.properties (set DB_PASSWORD first). */
@SpringBootTest
class LedgerIntegrityTest {
    @Autowired MoneyService money;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuditController audit;

    long admin() { return jdbc.queryForObject("SELECT id FROM users WHERE email='admin@ledgerline.dev'", Long.class); }

    long account(long fund) {
        long id = jdbc.queryForObject("INSERT INTO accounts(owner_user_id,name,type) VALUES (?,?,'USER') RETURNING id", Long.class, admin(), "test");
        if (fund > 0) money.deposit(admin(), "ADMIN", "t-" + UUID.randomUUID(), id, fund);
        return id;
    }

    long balance(long id) { return jdbc.queryForObject("SELECT balance_paise FROM accounts WHERE id=?", Long.class, id); }

    List<String> blast(int n, Callable<String> c) throws Exception {
        ExecutorService p = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) fs.add(p.submit(() -> { go.await(); return c.call(); }));
        go.countDown();
        List<String> out = new ArrayList<>();
        for (Future<String> f : fs) out.add(f.get(30, TimeUnit.SECONDS));
        p.shutdown();
        return out;
    }

    @Test
    void twentyParallelWithdrawalsNeverOverdraw() throws Exception {
        long acct = account(50_000);
        long adminId = admin();
        List<String> r = blast(20, () -> {
            try { money.withdraw(adminId, "ADMIN", "w-" + UUID.randomUUID(), acct, 10_000); return "OK"; }
            catch (ApiException e) { return e.code; }
        });
        assertEquals(5, r.stream().filter("OK"::equals).count());
        assertEquals(15, r.stream().filter("INSUFFICIENT_FUNDS"::equals).count());
        assertEquals(0, balance(acct));
    }

    @Test
    void sameKeyTenTimesCreatesOneTransaction() throws Exception {
        long acct = account(0);
        long adminId = admin();
        String key = "dup-" + UUID.randomUUID();
        blast(10, () -> { money.deposit(adminId, "ADMIN", key, acct, 10_000); return "x"; });
        assertEquals(10_000, balance(acct));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(DISTINCT txn_id) FROM ledger_entries WHERE account_id=?", Integer.class, acct));
    }

    @Test
    void reusingKeyWithDifferentAmountIsRejected() {
        long acct = account(0);
        long adminId = admin();
        String key = "mismatch-" + UUID.randomUUID();
        money.deposit(adminId, "ADMIN", key, acct, 100);
        ApiException e = assertThrows(ApiException.class, () -> money.deposit(adminId, "ADMIN", key, acct, 200));
        assertEquals(422, e.status);
    }

    @Test
    void missingIdempotencyKeyIsRejected() {
        long acct = account(0);
        long adminId = admin();
        assertEquals(400, assertThrows(ApiException.class, () -> money.deposit(adminId, "ADMIN", null, acct, 100)).status);
    }

    @Test
    void invalidAmountsAreRejected() {
        long acct = account(0);
        long adminId = admin();
        assertThrows(ApiException.class, () -> money.deposit(adminId, "ADMIN", "z-" + UUID.randomUUID(), acct, 0));
        assertThrows(ApiException.class, () -> money.deposit(adminId, "ADMIN", "n-" + UUID.randomUUID(), acct, -5));
    }

    @Test
    void holderCannotTouchSomeoneElsesAccount() {
        long bobId = jdbc.queryForObject("SELECT id FROM users WHERE email='bob@ledgerline.dev'", Long.class);
        long aliceAcct = jdbc.queryForObject("SELECT a.id FROM accounts a JOIN users u ON u.id=a.owner_user_id WHERE u.email='alice@ledgerline.dev' LIMIT 1", Long.class);
        ApiException e = assertThrows(ApiException.class, () -> money.withdraw(bobId, "HOLDER", "idor-" + UUID.randomUUID(), aliceAcct, 100));
        assertEquals(404, e.status);
    }

    @Test
    void ledgerEntriesAreAppendOnly() {
        long acct = account(100);
        assertThrows(Exception.class, () -> jdbc.update("UPDATE ledger_entries SET amount_paise=1 WHERE account_id=?", acct));
        assertThrows(Exception.class, () -> jdbc.update("DELETE FROM ledger_entries WHERE account_id=?", acct));
    }

    @Test
    void booksBalanceAfterActivity() {
        account(12_345);
        assertEquals(true, audit.conservation().get("balanced"));
    }
}
