package com.premisave.developer.service;

import com.premisave.developer.TestProps;
import com.premisave.developer.TestRepos;
import com.premisave.developer.client.ImageStorage;
import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;
import com.premisave.developer.view.DeveloperViews;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfilePictureServiceTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};

    /** A picture store in memory. */
    static final class FakeStorage implements ImageStorage {
        boolean on = true;
        boolean failOnUpload;
        boolean failOnDelete;
        final List<String> uploaded = new ArrayList<>();
        final List<String> types = new ArrayList<>();
        final List<byte[]> bytes = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();

        @Override public boolean configured() { return on; }
        @Override public String idFor(String developerId) { return "premisave/developers/" + developerId; }

        @Override
        public Stored upload(String developerId, byte[] image, String contentType) {
            if (failOnUpload) throw new DeveloperException(ErrorCode.UPSTREAM_UNAVAILABLE, "Profile pictures are not available right now. Try again later.");
            uploaded.add(developerId);
            types.add(contentType);
            bytes.add(image);
            return new Stored("https://res.cloudinary.com/demo/image/upload/v" + uploaded.size() + "/" + idFor(developerId) + ".jpg", idFor(developerId));
        }

        @Override
        public void delete(String publicId) {
            if (failOnDelete) throw new IllegalStateException("down");
            deleted.add(publicId);
        }
    }

    private final TestRepos.Developers repo = new TestRepos.Developers();
    private final FakeStorage storage = new FakeStorage();
    private final ProfilePictureService service = new ProfilePictureService(repo.repo, storage, TestProps.with("http://localhost:8084", null));

    private Developer developer() {
        Developer d = new Developer();
        d.setDeveloperId("dev_1");
        d.setEmail("jane@example.com");
        repo.store.put(d.getDeveloperId(), d);
        return d;
    }

    private static ByteArrayInputStream body(byte[] b) {
        return new ByteArrayInputStream(b);
    }

    private static ByteArrayInputStream bodyOf(int size, byte[] header) {
        byte[] b = new byte[size];
        System.arraycopy(header, 0, b, 0, header.length);
        return new ByteArrayInputStream(b);
    }

    @Test
    void aJpegPngOrWebpPictureIsStoredAndShownOnTheAccount() {
        Developer d = developer();
        Map<String, Object> out = service.upload(d, body(JPEG));
        assertTrue(((String) out.get("profilePictureUrl")).startsWith("https://res.cloudinary.com/"));
        assertNotNull(out.get("updatedAt"));
        assertEquals("image/jpeg", storage.types.get(0));
        assertArrayEquals(JPEG, storage.bytes.get(0));
        assertEquals(out.get("profilePictureUrl"), d.getProfilePictureUrl());
        assertEquals("premisave/developers/dev_1", d.getProfilePicturePublicId());
        assertEquals(out.get("profilePictureUrl"), DeveloperViews.developer(d).get("profilePictureUrl"));
        assertEquals(d, repo.store.get("dev_1"));

        service.upload(d, body(PNG));
        service.upload(d, body(WEBP));
        assertEquals(List.of("image/jpeg", "image/png", "image/webp"), storage.types);
    }

    @Test
    void aNewPictureReplacesTheOldOne() {
        Developer d = developer();
        service.upload(d, body(JPEG));
        String first = d.getProfilePictureUrl();
        service.upload(d, body(PNG));
        assertTrue(!first.equals(d.getProfilePictureUrl()));
        assertEquals(List.of("dev_1", "dev_1"), storage.uploaded);
    }

    @Test
    void whatIsNotAnImageIsRefusedWhateverItIsCalled() {
        Developer d = developer();
        for (byte[] bad : new byte[][] {
                "<html>hi</html>".getBytes(), "GIF89a......".getBytes(), "--boundary\r\nContent-Disposition: form-data".getBytes(),
                {1, 2, 3}, {(byte) 0xFF, (byte) 0xD8}, "RIFF....WAVE....".getBytes()}) {
            DeveloperException e = assertThrows(DeveloperException.class, () -> service.upload(d, body(bad)));
            assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
            assertEquals("picture", e.details().get(0).get("field"));
        }
        assertTrue(storage.uploaded.isEmpty());
        assertNull(d.getProfilePictureUrl());
    }

    @Test
    void anEmptyBodyIsRefused() {
        Developer d = developer();
        DeveloperException e = assertThrows(DeveloperException.class, () -> service.upload(d, body(new byte[0])));
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
    }

    @Test
    void aPictureOverTheLimitIsRefusedAndOneAtTheLimitIsKept() {
        Developer d = developer();
        int max = DeveloperProperties.Cloudinary.DEFAULT_MAX_BYTES;
        DeveloperException e = assertThrows(DeveloperException.class, () -> service.upload(d, bodyOf(max + 1, JPEG)));
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, e.code());
        assertTrue(e.getMessage().contains("5 MB"));
        assertTrue(storage.uploaded.isEmpty());
        service.upload(d, bodyOf(max, JPEG));
        assertEquals(1, storage.uploaded.size());
    }

    @Test
    void theLimitCanBeLoweredInTheSettings() {
        DeveloperProperties props = new DeveloperProperties(
                new DeveloperProperties.Jwt(TestProps.JWT_SECRET, null, null), TestProps.keys(),
                new DeveloperProperties.Wallet("http://localhost:8084", null, null, "k", null, 5),
                null, null, TestProps.fastSecurity(), null, null, null, null,
                new DeveloperProperties.Cloudinary("c", "k", "s", null, 20_000));
        ProfilePictureService small = new ProfilePictureService(repo.repo, storage, props);
        Developer d = developer();
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, assertThrows(DeveloperException.class, () -> small.upload(d, bodyOf(20_001, JPEG))).code());
        small.upload(d, bodyOf(20_000, JPEG));
    }

    @Test
    void withNoStorageSetUpNothingIsReadOrChanged() {
        storage.on = false;
        Developer d = developer();
        DeveloperException e = assertThrows(DeveloperException.class, () -> service.upload(d, body(JPEG)));
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, e.code());
        assertTrue(storage.uploaded.isEmpty());
        assertNull(d.getProfilePictureUrl());
    }

    @Test
    void aStoreThatFailsLeavesTheAccountAsItWas() {
        Developer d = developer();
        service.upload(d, body(JPEG));
        String before = d.getProfilePictureUrl();
        storage.failOnUpload = true;
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, assertThrows(DeveloperException.class, () -> service.upload(d, body(PNG))).code());
        assertEquals(before, d.getProfilePictureUrl());
    }

    @Test
    void removingDeletesTheStoredPictureAndClearsTheAccount() {
        Developer d = developer();
        service.upload(d, body(JPEG));
        Map<String, Object> out = service.remove(d);
        assertNull(out.get("profilePictureUrl"));
        assertEquals(List.of("premisave/developers/dev_1"), storage.deleted);
        assertNull(d.getProfilePictureUrl());
        assertNull(d.getProfilePicturePublicId());
        assertNull(DeveloperViews.developer(d).get("profilePictureUrl"));
    }

    @Test
    void removingWhenThereIsNoPictureIsNotAnError() {
        Developer d = developer();
        Map<String, Object> out = service.remove(d);
        assertNull(out.get("profilePictureUrl"));
        assertTrue(storage.deleted.isEmpty());
    }

    @Test
    void aStoreThatCannotDeleteStillLetsThePictureBeRemovedFromTheAccount() {
        Developer d = developer();
        service.upload(d, body(JPEG));
        storage.failOnDelete = true;
        service.remove(d);
        assertNull(d.getProfilePictureUrl());
    }

    @Test
    void aPictureCanBeRemovedWhenStorageIsSwitchedOffLater() {
        Developer d = developer();
        service.upload(d, body(JPEG));
        storage.on = false;
        service.remove(d);
        assertNull(d.getProfilePictureUrl());
        assertTrue(storage.deleted.isEmpty());
    }

    @Test
    void theImageTypeIsJudgedFromTheFirstBytes() {
        assertEquals("image/jpeg", ProfilePictureService.imageType(JPEG));
        assertEquals("image/png", ProfilePictureService.imageType(PNG));
        assertEquals("image/webp", ProfilePictureService.imageType(WEBP));
        assertNull(ProfilePictureService.imageType(new byte[0]));
        assertNull(ProfilePictureService.imageType("%PDF-1.7 something".getBytes()));
    }
}
