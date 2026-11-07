package com.ledgerline;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {
    private final JdbcTemplate jdbc;

    public AccountController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record CreateReq(@NotBlank @Email String ownerEmail, @NotBlank @Size(max = 60) String name) {}

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal UserPrincipal me) {
        String base = "SELECT a.id, a.name, a.type, a.balance_paise AS \"balancePaise\", u.email AS \"ownerEmail\" "
                + "FROM accounts a LEFT JOIN users u ON u.id = a.owner_user_id";
        if ("HOLDER".equals(me.role())) return jdbc.queryForList(base + " WHERE a.owner_user_id=? ORDER BY a.id", me.id());
        return jdbc.queryForList(base + " ORDER BY a.id");
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> create(@Valid @RequestBody CreateReq r) {
        List<Long> u = jdbc.queryForList("SELECT id FROM users WHERE email=? AND role='HOLDER'", Long.class, r.ownerEmail().toLowerCase());
        if (u.isEmpty()) throw new ApiException(404, "USER_NOT_FOUND", "No account-holder with that email");
        Long id = jdbc.queryForObject("INSERT INTO accounts(owner_user_id,name,type) VALUES (?,?,'USER') RETURNING id",
                Long.class, u.get(0), r.name());
        return Map.of("id", id);
    }

    @GetMapping("/{id}/entries")
    public List<Map<String, Object>> entries(@PathVariable long id, @AuthenticationPrincipal UserPrincipal me) {
        if ("HOLDER".equals(me.role())) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE id=? AND owner_user_id=?", Integer.class, id, me.id());
            if (n == null || n == 0) throw ApiException.notFound();
        }
        return jdbc.queryForList("SELECT id, txn_id AS \"txnId\", direction, amount_paise AS \"amountPaise\", "
                + "balance_after_paise AS \"balanceAfterPaise\", prev_hash AS \"prevHash\", entry_hash AS \"entryHash\", ts_ms AS \"tsMs\" "
                + "FROM ledger_entries WHERE account_id=? ORDER BY id DESC LIMIT 200", id);
    }
}
