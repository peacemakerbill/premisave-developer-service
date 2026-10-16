package com.premisave.developer.common;

import com.premisave.developer.enums.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns every failure into the standard envelope with a stable data.code. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DeveloperException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handle(DeveloperException ex, HttpServletRequest request) {
        Map<String, Object> data = base(ex.code(), request);
        data.putAll(ex.extra());
        if (ex.details() != null) data.put("details", ex.details());
        ResponseEntity.BodyBuilder out = ResponseEntity.status(ex.code().httpStatus());
        if (ex.code() == ErrorCode.RATE_LIMITED && ex.extra().get("retryAfterSeconds") != null) {
            out.header("Retry-After", String.valueOf(ex.extra().get("retryAfterSeconds")));
        }
        return out.body(ApiResponse.error(ex.getMessage(), data));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handle(MethodArgumentNotValidException ex,
                                                                    HttpServletRequest request) {
        List<Map<String, String>> details = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            details.add(Map.of("field", fe.getField(), "issue", String.valueOf(fe.getDefaultMessage())));
        }
        Map<String, Object> data = base(ErrorCode.VALIDATION_FAILED, request);
        data.put("details", details);
        return ResponseEntity.status(400).body(ApiResponse.error("Request validation failed", data));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handle(HttpMessageNotReadableException ex,
                                                                    HttpServletRequest request) {
        return ResponseEntity.status(400).body(ApiResponse.error(
                "Request body is missing or is not valid JSON for this endpoint", base(ErrorCode.VALIDATION_FAILED, request)));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleParam(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(400).body(ApiResponse.error(
                "A query parameter is missing or has the wrong type", base(ErrorCode.VALIDATION_FAILED, request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleUnexpected(Exception ex, HttpServletRequest request) {
        Map<String, Object> data = base(ErrorCode.INTERNAL_ERROR, request);
        log.error("Unexpected failure, requestId={}", data.get("requestId"), ex);
        return ResponseEntity.status(500).body(ApiResponse.error("Unexpected error", data));
    }

    private static Map<String, Object> base(ErrorCode code, HttpServletRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code.name());
        data.put("requestId", RequestIdFilter.of(request));
        return data;
    }
}
