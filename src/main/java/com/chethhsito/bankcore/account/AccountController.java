package com.chethhsito.bankcore.account;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@SecurityRequirement(name = "bearerAuth")
public class AccountController {
    private final AccountJdbcRepository accounts;

    public AccountController(AccountJdbcRepository accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    @Operation(summary = "Listar mis cuentas y saldos")
    public List<AccountResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return accounts.findByOwner(userId).stream()
                .map(account -> new AccountResponse(
                        account.id(), account.currency(),
                        account.availableBalance().toPlainString(), account.status()))
                .toList();
    }

    public record AccountResponse(UUID id, String currency, String availableBalance, String status) {
    }
}
