package com.premisave.developer.service;

import com.premisave.developer.client.ImageStorage;
import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;
import com.premisave.developer.repository.DeveloperRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The developer's profile picture. The picture arrives as the raw request body (not form data), is checked by looking at
 * its first bytes, never at the name or type the caller claims, and is stored in Cloudinary. One picture per developer:
 * a new one replaces the old one.
 */
@Service("developerProfilePictureService")
public class ProfilePictureService {

    private static final Logger log = LoggerFactory.getLogger(ProfilePictureService.class);

    private final DeveloperRepository developers;
    private final ImageStorage storage;
    private final int maxBytes;

    public ProfilePictureService(DeveloperRepository developers, ImageStorage storage, DeveloperProperties props) {
        this.developers = developers;
        this.storage = storage;
        this.maxBytes = props.cloudinary().maxBytes();
    }

    /** Stores the picture read from the body and makes it the developer's profile picture. */
    public Map<String, Object> upload(Developer d, InputStream body) {
        if (!storage.configured()) throw notAvailable();
        byte[] image = read(body);
        String type = imageType(image);
        if (type == null) {
            throw DeveloperException.validation("picture",
                    "must be a JPEG, PNG or WebP image sent as the request body, not as form data");
        }
        ImageStorage.Stored stored = storage.upload(d.getDeveloperId(), image, type);
        Instant now = Instant.now();
        d.setProfilePictureUrl(stored.url());
        d.setProfilePicturePublicId(stored.publicId());
        d.setProfilePictureUpdatedAt(now);
        d.setUpdatedAt(now);
        developers.save(d);
        return view(d);
    }

    /** Removes the picture. Removing one that is not there is not an error. */
    public Map<String, Object> remove(Developer d) {
        String publicId = d.getProfilePicturePublicId();
        if (publicId != null && storage.configured()) {
            try {
                storage.delete(publicId);
            } catch (RuntimeException e) {
                // The picture is hidden from the account either way. The next upload replaces the stored copy.
                log.warn("Could not remove the stored profile picture of developer {}: {}", d.getDeveloperId(), e.toString());
            }
        }
        Instant now = Instant.now();
        d.setProfilePictureUrl(null);
        d.setProfilePicturePublicId(null);
        d.setProfilePictureUpdatedAt(now);
        d.setUpdatedAt(now);
        developers.save(d);
        return view(d);
    }

    private byte[] read(InputStream body) {
        byte[] bytes;
        try {
            bytes = body.readNBytes(maxBytes + 1);
        } catch (IOException e) {
            throw DeveloperException.validation("picture", "could not be read");
        }
        if (bytes.length > maxBytes) {
            throw new DeveloperException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "The picture is larger than " + Math.max(1, maxBytes / 1_048_576) + " MB");
        }
        if (bytes.length == 0) throw DeveloperException.validation("picture", "is required");
        return bytes;
    }

    /** The content type of a JPEG, PNG or WebP image, judged from its first bytes. Null for anything else. */
    static String imageType(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "image/png";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static Map<String, Object> view(Developer d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("profilePictureUrl", d.getProfilePictureUrl());
        m.put("updatedAt", d.getProfilePictureUpdatedAt() == null ? null : d.getProfilePictureUpdatedAt().toString());
        return m;
    }

    private static DeveloperException notAvailable() {
        return new DeveloperException(ErrorCode.UPSTREAM_UNAVAILABLE,
                "Profile pictures are not available right now. Try again later.");
    }
}
