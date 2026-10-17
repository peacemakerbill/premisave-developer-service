package com.premisave.developer.enums;

/**
 * PENDING is a new account whose email address has not been confirmed yet. It cannot sign in, by password or through a
 * provider, and no token works for it. It becomes ACTIVE when the address is confirmed. DISABLED is set by Premisave
 * staff and blocks every sign in.
 */
public enum DeveloperStatus {
    PENDING, ACTIVE, DISABLED
}
