package com.chethhsito.bankcore.identity;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final IdentityService identity;
    private final SessionService sessions;

    public AuthController(IdentityService identity, SessionService sessions) {
        this.identity = identity;
        this.sessions = sessions;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Registrar usuario y crear su primera cuenta en PEN")
    public IdentityService.RegistrationResult register(@Valid @RequestBody RegistrationRequest request) {
        return identity.register(request.email(), request.password());
    }

    @PostMapping("/login")
    @Operation(summary = "Iniciar sesión y obtener access y refresh tokens")
    public SessionService.SessionTokens login(@Valid @RequestBody LoginRequest request) {
        return identity.login(request.email(), request.password());
    }

    @PostMapping("/refresh")
    @Operation(summary = "Renovar la sesión y rotar el refresh token")
    public SessionService.SessionTokens refresh(@Valid @RequestBody RefreshRequest request) {
        SessionService.SessionTokens tokens = sessions.refresh(request.refreshToken());
        if (tokens == null) throw new InvalidRefreshTokenException();
        return tokens;
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cerrar sesión y revocar la familia de refresh tokens")
    public void logout(@Valid @RequestBody RefreshRequest request) {
        sessions.logout(request.refreshToken());
    }

    public record RegistrationRequest(@NotBlank @Email String email,
                                      @NotBlank @Size(min = 8, max = 72) String password) {
    }

    public record LoginRequest(@NotBlank @Email String email,
                               @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }
}
