package com.ledgerline;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalHandler.class);

    public record Err(String code, String message, String traceId) {}

    private ResponseEntity<Err> out(int status, String code, String msg) {
        return ResponseEntity.status(status).body(new Err(code, msg, UUID.randomUUID().toString().substring(0, 8)));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Err> api(ApiException e) { return out(e.status, e.code, e.getMessage()); }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Err> invalid(MethodArgumentNotValidException e) {
        String why = e.getBindingResult().getFieldErrors().stream().findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage()).orElse("check the fields");
        return out(400, "VALIDATION_FAILED", "Invalid request: " + why);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Err> unreadable(HttpMessageNotReadableException e) {
        return out(400, "BAD_REQUEST", "Malformed request body");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Err> denied(AccessDeniedException e) { return out(403, "FORBIDDEN", "You do not have permission"); }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Err> nores(NoResourceFoundException e) { return out(404, "NOT_FOUND", "Not found"); }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Err> any(Exception e) {
        log.error("Unhandled", e);
        return out(500, "INTERNAL_ERROR", "Something went wrong");
    }
}
