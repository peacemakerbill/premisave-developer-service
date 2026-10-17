package com.premisave.developer.dto;

import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.exception.DeveloperException;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientCredentialsTest {

    private static String basic(String raw) {
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void basicAuthGivesKeyAndSecret() {
        ClientCredentials c = ClientCredentials.from(basic("pk_sandbox_abc:sk_sandbox_def"), null, null);
        assertEquals("pk_sandbox_abc", c.key());
        assertEquals("sk_sandbox_def", c.secret());
    }

    @Test
    void aSecretMayContainColons() {
        assertEquals("a:b", ClientCredentials.from(basic("key:a:b"), null, null).secret());
    }

    @Test
    void schemeIsCaseInsensitive() {
        assertEquals("k", ClientCredentials.from("basic " + Base64.getEncoder().encodeToString("k:s".getBytes()), null, null).key());
    }

    @Test
    void headerPairWorksWhenThereIsNoBasicAuth() {
        ClientCredentials c = ClientCredentials.from(null, " pk_x ", " sk_x ");
        assertEquals("pk_x", c.key());
        assertEquals("sk_x", c.secret());
    }

    @Test
    void anythingElseIsUnauthenticated() {
        String[][] cases = {
                {null, null, null}, {"Bearer abc", null, null}, {basic("nocolon"), null, null}, {basic(":secret"), null, null},
                {basic("key:"), null, null}, {"Basic !!!", null, null}, {null, "key", null}, {null, null, "secret"}, {null, " ", " "}};
        for (String[] c : cases) {
            DeveloperException e = assertThrows(DeveloperException.class, () -> ClientCredentials.from(c[0], c[1], c[2]));
            assertEquals(ErrorCode.UNAUTHENTICATED, e.code());
        }
    }
}
