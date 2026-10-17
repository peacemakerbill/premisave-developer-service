package com.premisave.developer.client;

/** Where profile pictures live. Cloudinary in production; a fake in tests. */
public interface ImageStorage {

    /** The stored picture: the https address to show, and the id needed to replace or remove it. */
    record Stored(String url, String publicId) {}

    /** False when no storage is set up, in which case upload and delete must not be called. */
    boolean configured();

    /** The id a developer's picture is stored under. The same id every time, so a new picture replaces the old one. */
    String idFor(String developerId);

    /** Stores the picture under the id, replacing any picture already there. */
    Stored upload(String developerId, byte[] image, String contentType);

    /** Removes the picture stored under the id. Removing one that is already gone is not an error. */
    void delete(String publicId);
}
