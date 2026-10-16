package com.premisave.developer.developer;

import com.premisave.developer.common.DeveloperException;

/** The rules a new password must meet. */
public final class PasswordPolicy {

    private PasswordPolicy() {}

    public static void check(int minLength, String password, String email) {
        if (password == null || password.length() < minLength) {
            throw DeveloperException.validation("password", "must be at least " + minLength + " characters");
        }
        if (password.length() > 128) throw DeveloperException.validation("password", "must be at most 128 characters");
        if (password.trim().isEmpty()) throw DeveloperException.validation("password", "cannot be blank");
        if (email != null && password.equalsIgnoreCase(email)) {
            throw DeveloperException.validation("password", "cannot be your email address");
        }
    }
}
