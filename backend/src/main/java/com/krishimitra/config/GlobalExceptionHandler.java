package com.krishimitra.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
class GlobalExceptionHandler {

    @ExceptionHandler(com.krishimitra.exception.ApiException.class)
    public org.springframework.http.ResponseEntity<com.krishimitra.dto.ErrorResponse> handleApiException(
            com.krishimitra.exception.ApiException ex, WebRequest req) {
        log.debug("ApiException: {} {}", ex.getStatus(), ex.getMessage());
        return org.springframework.http.ResponseEntity.status(ex.getStatus())
                .body(new com.krishimitra.dto.ErrorResponse(
                        ex.getStatus().value(),
                        ex.getStatus().getReasonPhrase(),
                        ex.getMessage(),
                        req.getDescription(false).replace("uri=", ""),
                        Instant.now(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public org.springframework.http.ResponseEntity<com.krishimitra.dto.ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, WebRequest req) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        f -> f.getDefaultMessage() != null ? f.getDefaultMessage() : "invalid",
                        (a, b) -> a));
        return org.springframework.http.ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new com.krishimitra.dto.ErrorResponse(
                        400, "Validation Failed",
                        "Request validation failed",
                        req.getDescription(false).replace("uri=", ""),
                        Instant.now(), fieldErrors));
    }

    @ExceptionHandler(Exception.class)
    public org.springframework.http.ResponseEntity<com.krishimitra.dto.ErrorResponse> handleGeneral(
            Exception ex, WebRequest req) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return org.springframework.http.ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new com.krishimitra.dto.ErrorResponse(
                        500, "Internal Server Error",
                        "An unexpected error occurred",
                        req.getDescription(false).replace("uri=", ""),
                        Instant.now(), null));
    }
}
