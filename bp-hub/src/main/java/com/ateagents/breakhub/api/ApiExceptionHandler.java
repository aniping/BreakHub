package com.ateagents.breakhub.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ProductException.class)
    public ResponseEntity<Map<String, Object>> productError(ProductException error) {
        return ResponseEntity.status(error.status()).body(Map.of(
                "code", error.code(),
                "message", error.getMessage()));
    }
}
