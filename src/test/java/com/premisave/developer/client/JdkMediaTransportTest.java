package com.premisave.developer.client;

import com.premisave.developer.Upstream;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdkMediaTransportTest {

    @Test
    void theBodyHasTheFieldsThenTheFileBetweenBoundaries() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("public_id", "premisave/developers/dev_1");
        fields.put("timestamp", "1");
        byte[] body = JdkMediaTransport.multipartBody("BOUND", fields, "file", "dev_1.png", "image/png", "PNGDATA".getBytes(StandardCharsets.UTF_8));
        String text = new String(body, StandardCharsets.UTF_8);
        assertEquals("--BOUND\r\n"
                + "Content-Disposition: form-data; name=\"public_id\"\r\n\r\npremisave/developers/dev_1\r\n"
                + "--BOUND\r\n"
                + "Content-Disposition: form-data; name=\"timestamp\"\r\n\r\n1\r\n"
                + "--BOUND\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"dev_1.png\"\r\n"
                + "Content-Type: image/png\r\n\r\nPNGDATA\r\n"
                + "--BOUND--\r\n", text);
    }

    @Test
    void quotesAndLineBreaksCannotStartAnotherPart() {
        byte[] body = JdkMediaTransport.multipartBody("B", Map.of("na\"me\r\nx", "v"), "file", "a\"\r\nb.png", "image/png", new byte[] {1});
        String text = new String(body, StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("name=\"namex\""));
        assertTrue(text.contains("filename=\"ab.png\""));
        assertFalse(text.contains("a\"\r\nb"));
    }

    @Test
    void theFileBytesArriveUnchanged() {
        byte[] file = new byte[256];
        for (int i = 0; i < 256; i++) file[i] = (byte) i;
        byte[] body = JdkMediaTransport.multipartBody("B", Map.of(), "file", "x.png", "image/png", file);
        String head = "--B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"x.png\"\r\nContent-Type: image/png\r\n\r\n";
        int start = head.length();
        for (int i = 0; i < 256; i++) assertEquals(file[i], body[start + i]);
    }

    @Test
    void anUploadGoesOutAsMultipartFormData() throws Exception {
        try (Upstream up = new Upstream()) {
            up.replyBody = "{\"secure_url\":\"https://x/y.png\"}";
            HttpTransport.Reply r = new JdkMediaTransport().postMultipart(up.url() + "/v1_1/c/image/upload",
                    Map.of("public_id", "a/b"), "file", "dev_1.png", "image/png", "ABCDEF".getBytes(StandardCharsets.UTF_8));
            assertEquals(200, r.status());
            Upstream.Seen seen = up.seen.get(0);
            assertEquals("POST", seen.method());
            assertTrue(seen.headers().get("content-type").startsWith("multipart/form-data; boundary=premisave"));
            assertTrue(seen.body().contains("name=\"public_id\"\r\n\r\na/b"));
            assertTrue(seen.body().contains("filename=\"dev_1.png\""));
            assertTrue(seen.body().contains("ABCDEF"));
        }
    }
}
