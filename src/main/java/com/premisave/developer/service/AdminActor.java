package com.premisave.developer.service;

import com.premisave.developer.model.Developer;

/**
 * Who is making an admin call: a signed in administrator, or Premisave tooling using the internal API key. Used for the
 * audit trail and to stop an administrator from locking themselves out.
 */
public record AdminActor(String id, String email) {

    /** A call made with the internal API key, which belongs to no account. */
    public static final AdminActor INTERNAL = new AdminActor("internal-key", null);

    public static AdminActor of(Developer admin) {
        return admin == null ? INTERNAL : new AdminActor(admin.getDeveloperId(), admin.getEmail());
    }

    public boolean is(String developerId) {
        return id.equals(developerId);
    }
}
