package com.premisave.developer.client;

import com.premisave.developer.Upstream;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdkHttpTransportTest {

    private final JdkHttpTransport transport = new JdkHttpTransport();

    @Test
    void postsAFormWithEncodedValuesAndTheGivenHeaders() throws Exception {
        try (Upstream up = new Upstream()) {
            up.replyBody = "{\"access_token\":\"abc\"}";
            Map<String, String> form = new LinkedHashMap<>();
            form.put("code", "a b&c=d");
            form.put("redirect_uri", "https://portal.example.com/cb?x=1");
            HttpTransport.Reply r = transport.postForm(up.url() + "/token", Map.of("Accept", "application/json"), form);
            assertEquals(200, r.status());
            assertEquals("{\"access_token\":\"abc\"}", r.body());
            Upstream.Seen seen = up.seen.get(0);
            assertEquals("POST", seen.method());
            assertEquals("application/x-www-form-urlencoded", seen.headers().get("content-type"));
            assertEquals("application/json", seen.headers().get("accept"));
            assertEquals("code=a+b%26c%3Dd&redirect_uri=https%3A%2F%2Fportal.example.com%2Fcb%3Fx%3D1", seen.body());
        }
    }

    @Test
    void getsWithHeadersAndReturnsErrorStatusesAsReplies() throws Exception {
        try (Upstream up = new Upstream()) {
            up.status = 401;
            up.replyBody = "{\"message\":\"Bad credentials\"}";
            HttpTransport.Reply r = transport.get(up.url() + "/user", Map.of("Authorization", "Bearer x"));
            assertEquals(401, r.status());
            assertTrue(r.body().contains("Bad credentials"));
            assertEquals("Bearer x", up.seen.get(0).headers().get("authorization"));
        }
    }

    @Test
    void redirectsAreNotFollowed() throws Exception {
        try (Upstream up = new Upstream()) {
            up.status = 302;
            up.replyHeaders.put("Location", "http://127.0.0.1:1/elsewhere");
            up.replyBody = "";
            HttpTransport.Reply r = transport.get(up.url() + "/user", Map.of());
            assertEquals(302, r.status());
            assertEquals(1, up.seen.size());
        }
    }

    @Test
    void aReplyThatIsTooLargeIsRefused() throws Exception {
        try (Upstream up = new Upstream()) {
            up.replyBody = "x".repeat(300_000);
            assertThrows(IOException.class, () -> transport.get(up.url() + "/big", Map.of()));
        }
    }

    @Test
    void aServerThatIsNotThereIsAnIoError() {
        assertThrows(IOException.class, () -> transport.get("http://127.0.0.1:1/none", Map.of()));
    }
}
