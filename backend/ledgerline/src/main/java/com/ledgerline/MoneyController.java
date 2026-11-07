package com.ledgerline;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class MoneyController {
    private final MoneyService money;

    public MoneyController(MoneyService money) { this.money = money; }

    public record AmountReq(@NotNull @Positive Long amountPaise) {}
    public record TransferReq(@NotNull Long toAccountId, @NotNull @Positive Long amountPaise) {}

    @PostMapping("/accounts/{id}/deposit")
    @PreAuthorize("hasAnyRole('HOLDER','ADMIN')")
    public Map<String, Object> deposit(@PathVariable long id, @Valid @RequestBody AmountReq r,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                       @AuthenticationPrincipal UserPrincipal me) {
        return money.deposit(me.id(), me.role(), key, id, r.amountPaise());
    }

    @PostMapping("/accounts/{id}/withdraw")
    @PreAuthorize("hasAnyRole('HOLDER','ADMIN')")
    public Map<String, Object> withdraw(@PathVariable long id, @Valid @RequestBody AmountReq r,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                        @AuthenticationPrincipal UserPrincipal me) {
        return money.withdraw(me.id(), me.role(), key, id, r.amountPaise());
    }

    @PostMapping("/accounts/{id}/transfer")
    @PreAuthorize("hasAnyRole('HOLDER','ADMIN')")
    public Map<String, Object> transfer(@PathVariable long id, @Valid @RequestBody TransferReq r,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                        @AuthenticationPrincipal UserPrincipal me) {
        return money.transfer(me.id(), me.role(), key, id, r.toAccountId(), r.amountPaise());
    }

    @PostMapping("/transactions/{txnId}/reverse")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> reverse(@PathVariable UUID txnId,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                       @AuthenticationPrincipal UserPrincipal me) {
        return money.reverse(me.id(), key, txnId);
    }
}
