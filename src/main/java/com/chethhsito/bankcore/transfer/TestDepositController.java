package com.chethhsito.bankcore.transfer;

import com.chethhsito.bankcore.observability.BankcoreMetrics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local")
@RequestMapping("/api/v1/test-deposits")
@SecurityRequirement(name = "bearerAuth")
public class TestDepositController {
    private static final Logger log = LoggerFactory.getLogger(TestDepositController.class);
    private final TestDepositService deposits;
    private final BankcoreMetrics metrics;

    public TestDepositController(TestDepositService deposits, BankcoreMetrics metrics) {
        this.deposits = deposits;
        this.metrics = metrics;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Cargar dinero simulado en una cuenta propia (solo perfil local)")
    public TransferController.TransferResponse create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TestDepositRequest request) {
        TransferResult result = deposits.deposit(UUID.fromString(jwt.getSubject()),
                request.destinationAccountId(), new BigDecimal(request.amount()));
        metrics.depositCompleted();
        log.info("Test deposit {} completed", result.id());
        return TransferController.TransferResponse.from(result);
    }

    public record TestDepositRequest(
            @NotNull UUID destinationAccountId,
            @NotNull @Pattern(regexp = "^(?!0\\.00$)(0|[1-9][0-9]{0,16})\\.[0-9]{2}$") String amount
    ) {
    }
}
