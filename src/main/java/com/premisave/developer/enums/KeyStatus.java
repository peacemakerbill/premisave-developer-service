package com.premisave.developer.enums;

public enum KeyStatus {
    /** Accepted. */
    ACTIVE,
    /** Replaced by a newer key; still accepted until expiresAt so the merchant can switch over. */
    ROTATING,
    /** Never accepted again. */
    REVOKED
}
