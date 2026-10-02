package com.chethhsito.bankcore.transfer;

import com.chethhsito.bankcore.identity.InvalidCredentialsException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(TransferRejectedException.class)
    ResponseEntity<ErrorResponse> transferRejected(TransferRejectedException error) {
        HttpStatus status = switch (error.code()) {
            case "INVALID_REQUEST" -> HttpStatus.BAD_REQUEST;
            case "FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "ACCOUNT_NOT_FOUND", "TRANSFER_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(new ErrorResponse(error.code(), message(error.code())));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ErrorResponse> idempotencyConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(
                "IDEMPOTENCY_CONFLICT", "La clave ya corresponde a otra solicitud."));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ErrorResponse> invalidCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(
                "INVALID_CREDENTIALS", "Correo o password incorrectos."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> dataConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(
                "CONFLICT", "El recurso ya existe o no cumple una restriccion."));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class,
            ConstraintViolationException.class})
    ResponseEntity<ErrorResponse> invalidRequest() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(
                "INVALID_REQUEST", "La solicitud no cumple el formato esperado."));
    }

    private static String message(String code) {
        return switch (code) {
            case "FORBIDDEN" -> "No puedes operar esa cuenta.";
            case "ACCOUNT_NOT_FOUND", "TRANSFER_NOT_FOUND" -> "Recurso no encontrado.";
            case "INSUFFICIENT_FUNDS" -> "Saldo insuficiente.";
            case "SAME_SOURCE_DESTINATION" -> "Las cuentas deben ser distintas.";
            case "ACCOUNT_INACTIVE" -> "Una de las cuentas no esta activa.";
            default -> "La operacion fue rechazada.";
        };
    }

    public record ErrorResponse(String code, String message) {
    }
}
