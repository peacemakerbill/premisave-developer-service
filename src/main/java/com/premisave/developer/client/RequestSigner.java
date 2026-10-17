package com.premisave.developer.client;

import com.premisave.developer.util.Hashing;

/**
 * Signs a call to the wallet service when request signing is switched on there.
 *
 * signingString = timestamp + "\n" + METHOD + "\n" + pathWithQuery + "\n" + hex(sha256(body))
 * signature     = hex(hmacSha256(secret, signingString))
 */
public final class RequestSigner {

    private RequestSigner() {}

    public static String signingString(String timestamp, String method, String pathWithQuery, byte[] body) {
        return timestamp + "\n" + method.toUpperCase() + "\n" + pathWithQuery + "\n"
                + Hashing.sha256Hex(body == null ? new byte[0] : body);
    }

    public static String sign(String secret, String timestamp, String method, String pathWithQuery, byte[] body) {
        return Hashing.hmacSha256Hex(secret, signingString(timestamp, method, pathWithQuery, body));
    }
}
