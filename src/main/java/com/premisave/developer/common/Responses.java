package com.premisave.developer.common;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.function.Function;

/** Small helpers so controllers stay one line per endpoint. */
public final class Responses {

    private Responses() {}

    public static ResponseEntity<ApiResponse<Map<String, Object>>> ok(String message, Map<String, Object> data) {
        return ResponseEntity.ok(ApiResponse.success(message, data));
    }

    public static ResponseEntity<ApiResponse<Map<String, Object>>> created(String message, Map<String, Object> data) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(message, data));
    }

    public static <T> ResponseEntity<ApiResponse<Map<String, Object>>> page(String message, Page<T> page,
                                                                          Function<T, Map<String, Object>> view) {
        return ResponseEntity.ok(ApiResponse.success(message, PageViews.of(page, view)));
    }
}
