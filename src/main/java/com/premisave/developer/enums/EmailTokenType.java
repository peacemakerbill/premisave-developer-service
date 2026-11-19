package com.premisave.developer.enums;

/** What a link sent by email is for. LINK_PROVIDER joins a Google, Facebook or GitHub account to an existing developer account. */
public enum EmailTokenType {
    VERIFY_EMAIL, RESET_PASSWORD, LINK_PROVIDER
}
