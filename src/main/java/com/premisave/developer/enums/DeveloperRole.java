package com.premisave.developer.enums;

/**
 * What an account may do. A DEVELOPER builds on the platform: businesses, apps and keys of their own. An ADMIN is Premisave
 * staff and can also manage every developer, app, key and transfer through /api/v1/admin. The role is read from the
 * database on every request, so a change takes effect at once and no token has to be reissued.
 */
public enum DeveloperRole {
    DEVELOPER, ADMIN
}
