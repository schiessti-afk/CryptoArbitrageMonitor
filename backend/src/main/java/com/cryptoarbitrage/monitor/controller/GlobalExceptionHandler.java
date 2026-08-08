package com.cryptoarbitrage.monitor.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Unmapped routes must surface as 404, not fall through to the generic 500 handler below.
     * Without this, a missing/renamed endpoint (e.g. a typo'd path or a build that didn't pick up
     * a new @GetMapping) reports as a server error, which sends debugging in the wrong direction.
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNotFound(Exception ex, WebRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Not found");
        body.put("timestamp", Instant.now());
        body.put("path", request.getDescription(false).replace("uri=", ""));
        return ResponseEntity.status(404).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.getMessage());
        body.put("timestamp", Instant.now());
        body.put("path", request.getDescription(false).replace("uri=", ""));

        return ResponseEntity.status(500).body(body);
    }
}
