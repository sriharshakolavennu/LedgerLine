package com.ledgerline;

import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Demo data on first start only. Demo password is for local use; change SEED_PASSWORD env var if you like. */
@Component
public class Seeder implements CommandLineRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder enc;
    private final MoneyService money;

    public Seeder(JdbcTemplate jdbc, PasswordEncoder enc, MoneyService money) {
        this.jdbc = jdbc; this.enc = enc; this.money = money;
    }

    @Override
    public void run(String... args) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class) > 0) return;
        String pw = enc.encode(System.getenv().getOrDefault("SEED_PASSWORD", "Passw0rd!"));
        long admin = user("admin@ledgerline.dev", pw, "ADMIN");
        user("auditor@ledgerline.dev", pw, "AUDITOR");
        long alice = user("alice@ledgerline.dev", pw, "HOLDER");
        long bob = user("bob@ledgerline.dev", pw, "HOLDER");
        jdbc.update("INSERT INTO accounts(name, type) VALUES ('CASH_IN_CLEARING','SYSTEM'),('CASH_OUT_CLEARING','SYSTEM')");
        long a1 = acct(alice, "Alice Wallet");
        long a2 = acct(alice, "Alice Mess Card");
        long b1 = acct(bob, "Bob Wallet");
        money.deposit(admin, "ADMIN", "seed-" + UUID.randomUUID(), a1, 500_000);
        money.deposit(admin, "ADMIN", "seed-" + UUID.randomUUID(), a2, 150_000);
        money.deposit(admin, "ADMIN", "seed-" + UUID.randomUUID(), b1, 200_000);
    }

    private long user(String email, String hash, String role) {
        return jdbc.queryForObject("INSERT INTO users(email,password_hash,role) VALUES (?,?,?) RETURNING id", Long.class, email, hash, role);
    }

    private long acct(long owner, String name) {
        return jdbc.queryForObject("INSERT INTO accounts(owner_user_id,name,type) VALUES (?,?,'USER') RETURNING id", Long.class, owner, name);
    }
}
