package com.premisave.developer.view;

import com.premisave.developer.exception.DeveloperException;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Shared list response shape and paging input handling. */
public final class PageViews {

    private static final int MAX_SIZE = 100;

    private PageViews() {}

    public static <T> Map<String, Object> of(Page<T> page, Function<T, Map<String, Object>> view) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> content = page.getContent().stream().map(view).toList();
        m.put("content", content);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("size", page.getSize());
        info.put("number", page.getNumber());
        info.put("totalElements", page.getTotalElements());
        info.put("totalPages", page.getTotalPages());
        m.put("page", info);
        return m;
    }

    /** page is zero based, size is capped at 100, newest first. */
    public static Pageable pageable(Integer page, Integer size) {
        int p = page == null ? 0 : page;
        int s = size == null ? 20 : size;
        if (p < 0) throw DeveloperException.validation("page", "must be zero or more");
        if (s < 1 || s > MAX_SIZE) throw DeveloperException.validation("size", "must be between 1 and " + MAX_SIZE);
        return PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "createdAt"));
    }
}
