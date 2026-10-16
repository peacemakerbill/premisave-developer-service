package com.premisave.developer.oauth;

/**
 * What a provider says about the person. subject is the provider's own stable id. emailVerified is true only when the
 * provider itself vouches that the person controls the address.
 */
public record OAuthProfile(OAuthProvider provider, String subject, String email, boolean emailVerified, String name) {}
