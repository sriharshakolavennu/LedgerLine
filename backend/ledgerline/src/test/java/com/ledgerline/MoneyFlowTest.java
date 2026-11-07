package com.ledgerline;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Signup, deposit, withdraw and history, end to end through the services. Needs the local DB and DB_PASSWORD. */
@SpringBootTest
class MoneyFlowTest {
    @Autowired AuthController auth;
    @Autowired MoneyService money;
    @Autowired HistoryController history;
    @Autowired JdbcTemplate jdbc;

    record U(long id, String email, long acct) {}

    U newUser() {
        String email = "u-" + UUID.randomUUID() + "@test.dev";
        long id = ((Number) auth.signup(new AuthController.SignupReq(email, "Passw0rd!")).get("id")).longValue();
        long acct = jdbc.queryForObject("SELECT id FROM accounts WHERE owner_user_id=?", Long.class, id);
        return new U(id, email, acct);
    }

    long balance(long acct) { return jdbc.queryForObject("SELECT balance_paise FROM accounts WHERE id=?", Long.class, acct); }
    String key() { return "t-" + UUID.randomUUID(); }
    List<Map<String, Object>> rows(U u) { return history.history(null, 100, new UserPrincipal(u.id(), u.email(), "HOLDER")); }

    @Test
    void signupCreatesHolderWithOneWallet() {
        U u = newUser();
        assertEquals("HOLDER", jdbc.queryForObject("SELECT role FROM users WHERE id=?", String.class, u.id()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE owner_user_id=?", Integer.class, u.id()));
        assertEquals(0, balance(u.acct()));
    }

    @Test
    void signupRejectsDuplicateEmailAndWeakPassword() {
        U u = newUser();
        assertEquals(409, assertThrows(ApiException.class, () -> auth.signup(new AuthController.SignupReq(u.email(), "Passw0rd!"))).status);
        assertEquals(400, assertThrows(ApiException.class, () -> auth.signup(new AuthController.SignupReq("x-" + UUID.randomUUID() + "@test.dev", "onlyletters"))).status);
    }

    @Test
    void depositThenWithdrawUpdatesBalanceAndHistory() {
        U u = newUser();
        money.deposit(u.id(), "HOLDER", key(), u.acct(), 10_000);
        assertEquals(10_000, balance(u.acct()));
        money.withdraw(u.id(), "HOLDER", key(), u.acct(), 2_500);
        assertEquals(7_500, balance(u.acct()));

        List<Map<String, Object>> h = rows(u);
        assertEquals(2, h.size());
        assertEquals("Money withdrawn", h.get(0).get("description"));
        assertEquals("DEBIT", h.get(0).get("direction"));
        assertEquals(7_500L, ((Number) h.get(0).get("balanceAfterPaise")).longValue());
        assertEquals("Money added", h.get(1).get("description"));
    }

    @Test
    void overdrawIsRejectedAndBalanceIsUnchanged() {
        U u = newUser();
        money.deposit(u.id(), "HOLDER", key(), u.acct(), 1_000);
        ApiException e = assertThrows(ApiException.class, () -> money.withdraw(u.id(), "HOLDER", key(), u.acct(), 1_001));
        assertEquals("INSUFFICIENT_FUNDS", e.code);
        assertEquals(1_000, balance(u.acct()));
        assertEquals(1, rows(u).size());          // the failed attempt left no ledger entry
    }

    @Test
    void withdrawingExactBalanceLeavesZero() {
        U u = newUser();
        money.deposit(u.id(), "HOLDER", key(), u.acct(), 1_000);
        money.withdraw(u.id(), "HOLDER", key(), u.acct(), 1_000);
        assertEquals(0, balance(u.acct()));
    }

    @Test
    void retryWithSameKeyIsAppliedOnce() {
        U u = newUser();
        String k = key();
        money.deposit(u.id(), "HOLDER", k, u.acct(), 5_000);
        assertEquals(true, money.deposit(u.id(), "HOLDER", k, u.acct(), 5_000).get("replayed"));
        assertEquals(5_000, balance(u.acct()));
    }

    @Test
    void transferShowsOnBothSidesAndHidesOtherAccountNames() {
        U a = newUser(), b = newUser();
        money.deposit(a.id(), "HOLDER", key(), a.acct(), 4_000);
        money.transfer(a.id(), "HOLDER", key(), a.acct(), b.acct(), 1_500);
        assertEquals("Sent to account #" + b.acct(), rows(a).get(0).get("description"));
        assertEquals("Received from account #" + a.acct(), rows(b).get(0).get("description"));
        assertTrue(rows(b).stream().allMatch(r -> ((Number) r.get("accountId")).longValue() == b.acct()));
    }
}
