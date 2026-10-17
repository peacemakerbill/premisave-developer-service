package com.premisave.developer.client;

import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.exception.DeveloperException;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudinaryImageStorageTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1_315_060_510L), ZoneOffset.UTC);

    private final FakeMediaTransport http = new FakeMediaTransport();
    private final DeveloperProperties.Cloudinary config =
            new DeveloperProperties.Cloudinary("democloud", "key123", "secret456", null, null);
    private final CloudinaryImageStorage storage = new CloudinaryImageStorage(config, http, CLOCK);

    @Test
    void theSignatureMatchesTheExampleInCloudinarysDocumentation() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("timestamp", "1315060510");
        params.put("public_id", "sample_image");
        params.put("eager", "w_400,h_300,c_pad|w_260,h_200,c_crop");
        assertEquals("bfd09f95f331f558cbd1320e67aa8d488770583e", CloudinaryImageStorage.sign(params, "abcd"));
    }

    @Test
    void anUploadIsSignedAndSentToTheCloudsUploadAddress() {
        ImageStorage.Stored stored = storage.upload("dev_1", new byte[] {1, 2, 3}, "image/png");
        assertEquals("https://res.cloudinary.com/demo/image/upload/v12/premisave/developers/dev_1.jpg", stored.url());
        assertEquals("premisave/developers/dev_1", stored.publicId());

        FakeMediaTransport.Upload sent = http.uploads.get(0);
        assertEquals("https://api.cloudinary.com/v1_1/democloud/image/upload", sent.url());
        assertEquals("file", sent.fileField());
        assertEquals("dev_1.png", sent.fileName());
        assertEquals("image/png", sent.contentType());
        assertEquals(3, sent.file().length);
        Map<String, String> f = sent.fields();
        assertEquals("premisave/developers/dev_1", f.get("public_id"));
        assertEquals("true", f.get("overwrite"));
        assertEquals("true", f.get("invalidate"));
        assertEquals("1315060510", f.get("timestamp"));
        assertEquals("c_limit,h_1024,w_1024", f.get("transformation"));
        assertEquals("key123", f.get("api_key"));
        Map<String, String> signed = new LinkedHashMap<>(f);
        signed.remove("api_key");
        signed.remove("signature");
        assertEquals(CloudinaryImageStorage.sign(signed, "secret456"), f.get("signature"));
    }

    @Test
    void theSecretIsNeverSentOnlyTheSignature() {
        storage.upload("dev_1", new byte[] {1}, "image/jpeg");
        assertFalse(http.uploads.get(0).fields().containsValue("secret456"));
        storage.delete("premisave/developers/dev_1");
        assertFalse(http.forms.get(0).form().containsValue("secret456"));
    }

    @Test
    void deletingSignsTheDestroyCall() {
        http.reply = new HttpTransport.Reply(200, "{\"result\":\"ok\"}");
        storage.delete("premisave/developers/dev_1");
        FakeMediaTransport.Form sent = http.forms.get(0);
        assertEquals("https://api.cloudinary.com/v1_1/democloud/image/destroy", sent.url());
        assertEquals("premisave/developers/dev_1", sent.form().get("public_id"));
        assertEquals("true", sent.form().get("invalidate"));
        assertEquals("key123", sent.form().get("api_key"));
        Map<String, String> signed = new LinkedHashMap<>(sent.form());
        signed.remove("api_key");
        signed.remove("signature");
        assertEquals(CloudinaryImageStorage.sign(signed, "secret456"), sent.form().get("signature"));
    }

    @Test
    void theFolderIsCleanedAndUsedInTheId() {
        DeveloperProperties.Cloudinary c = new DeveloperProperties.Cloudinary("democloud", "k", "s", " /acme/people/ ", null);
        assertEquals("acme/people/dev_9", new CloudinaryImageStorage(c, http, CLOCK).idFor("dev_9"));
        assertEquals("premisave/developers/dev_9", storage.idFor("dev_9"));
    }

    @Test
    void aStoreThatAnswersWithAnErrorIsReportedAsTemporarilyUnavailable() {
        http.reply = new HttpTransport.Reply(401, "{\"error\":{\"message\":\"Invalid Signature\"}}");
        DeveloperException e = assertThrows(DeveloperException.class, () -> storage.upload("dev_1", new byte[] {1}, "image/png"));
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, e.code());
        assertFalse(e.getMessage().contains("Signature"));
        assertFalse(e.getMessage().contains("secret456"));
    }

    @Test
    void aStoreThatCannotBeReachedIsReportedAsTemporarilyUnavailable() {
        http.fail = true;
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE,
                assertThrows(DeveloperException.class, () -> storage.upload("dev_1", new byte[] {1}, "image/png")).code());
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE,
                assertThrows(DeveloperException.class, () -> storage.delete("premisave/developers/dev_1")).code());
    }

    @Test
    void anAnswerWithoutAnHttpsAddressIsRefused() {
        http.reply = new HttpTransport.Reply(200, "{\"secure_url\":\"http://example.com/a.jpg\"}");
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE,
                assertThrows(DeveloperException.class, () -> storage.upload("dev_1", new byte[] {1}, "image/png")).code());
        http.reply = new HttpTransport.Reply(200, "not json");
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE,
                assertThrows(DeveloperException.class, () -> storage.upload("dev_1", new byte[] {1}, "image/png")).code());
    }

    @Test
    void withoutCredentialsNothingIsSent() {
        CloudinaryImageStorage off = new CloudinaryImageStorage(new DeveloperProperties.Cloudinary(null, null, null, null, null), http, CLOCK);
        assertFalse(off.configured());
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE,
                assertThrows(DeveloperException.class, () -> off.upload("dev_1", new byte[] {1}, "image/png")).code());
        assertTrue(http.uploads.isEmpty());
    }
}
