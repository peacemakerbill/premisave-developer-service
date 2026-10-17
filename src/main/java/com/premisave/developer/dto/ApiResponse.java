package com.premisave.developer.dto;

import java.time.LocalDateTime;

/** The envelope every endpoint answers with, the same shape the wallet service uses. */
public class ApiResponse<T> {

    private final boolean success;
    private final String message;
    private final T data;
    private final LocalDateTime timestamp = LocalDateTime.now();

    private ApiResponse(boolean success, String message, T data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> success(String message, T data) { return new ApiResponse<>(true, message, data); }

    public static <T> ApiResponse<T> error(String message, T data) { return new ApiResponse<>(false, message, data); }

    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
    public T getData() { return data; }
    public LocalDateTime getTimestamp() { return timestamp; }
}
