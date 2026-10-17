package com.premisave.developer.dto;

import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.exception.DeveloperException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** The key and secret presented with a request: HTTP Basic (key as user, secret as password) or X-Client-Key and X-Client-Secret. */
public record ClientCredentials(String key, String secret) {

    public static ClientCredentials from(String authorization, String keyHeader, String secretHeader) {
        if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            String decoded;
            try {
                decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw missing();
            }
            int colon = decoded.indexOf(':');
            if (colon > 0 && colon < decoded.length() - 1) {
                return new ClientCredentials(decoded.substring(0, colon), decoded.substring(colon + 1));
            }
            throw missing();
        }
        if (keyHeader != null && !keyHeader.isBlank() && secretHeader != null && !secretHeader.isBlank()) {
            return new ClientCredentials(keyHeader.trim(), secretHeader.trim());
        }
        throw missing();
    }

    private static DeveloperException missing() {
        return new DeveloperException(ErrorCode.UNAUTHENTICATED,
                "Send your API key and secret as HTTP Basic credentials, or in X-Client-Key and X-Client-Secret");
    }
}
