package com.premisave.developer.exception;

import com.premisave.developer.enums.ErrorCode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Single exception type for every expected failure. The advice turns it into the standard envelope. */
public class DeveloperException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final ErrorCode code;
    private final Map<String, Object> extra = new LinkedHashMap<>();
    private List<Map<String, String>> details;

    public DeveloperException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() { return code; }

    public Map<String, Object> extra() { return extra; }

    public List<Map<String, String>> details() { return details; }

    public DeveloperException with(String key, Object value) {
        extra.put(key, value);
        return this;
    }

    public DeveloperException withDetails(List<Map<String, String>> details) {
        this.details = details;
        return this;
    }

    public static DeveloperException validation(String field, String issue) {
        return new DeveloperException(ErrorCode.VALIDATION_FAILED, field + ": " + issue)
                .withDetails(List.of(Map.of("field", field, "issue", issue)));
    }

    public static DeveloperException invalidState(String message) {
        return new DeveloperException(ErrorCode.INVALID_STATE, message);
    }
}
