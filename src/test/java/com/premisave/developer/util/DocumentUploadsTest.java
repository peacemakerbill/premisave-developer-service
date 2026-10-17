package com.premisave.developer.util;

import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.exception.DeveloperException;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The conversion of a form file into the JSON the wallet service takes. */
class DocumentUploadsTest {

    private static DeveloperException fails(Runnable r) {
        DeveloperException e = assertThrows(DeveloperException.class, r::run);
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
        return e;
    }

    @Test
    void theTypeMayBeWrittenInOrdinaryWords() {
        assertEquals("BUSINESS_REGISTRATION", DocumentUploads.type("business registration"));
        assertEquals("BUSINESS_REGISTRATION", DocumentUploads.type("Business-Registration"));
        assertEquals("TAX_COMPLIANCE", DocumentUploads.type(" tax_compliance "));
        assertEquals("DIRECTOR_ID", DocumentUploads.type("DIRECTOR_ID"));
        assertEquals("AUTHORISATION_LETTER", DocumentUploads.type("authorisation letter"));
    }

    @Test
    void aMissingTypeIsOtherAndAnUnknownOneIsRefused() {
        assertEquals("OTHER", DocumentUploads.type(null));
        assertEquals("OTHER", DocumentUploads.type("  "));
        DeveloperException e = fails(() -> DocumentUploads.type("passport"));
        assertEquals("type", e.details().get(0).get("field"));
    }

    @Test
    void anAllowedContentTypeIsKeptAndParametersAreDropped() {
        assertEquals("application/pdf", DocumentUploads.contentType("Application/PDF; charset=binary", "x.bin"));
        assertEquals("image/png", DocumentUploads.contentType("image/png", "x"));
    }

    @Test
    void otherwiseTheExtensionDecides() {
        assertEquals("application/pdf", DocumentUploads.contentType("application/octet-stream", "Cert.PDF"));
        assertEquals("image/jpeg", DocumentUploads.contentType(null, "id.jpeg"));
        assertEquals("image/jpeg", DocumentUploads.contentType("", "id.JPG"));
        assertEquals("image/png", DocumentUploads.contentType(null, "scan.png"));
        fails(() -> DocumentUploads.contentType("text/html", "page.html"));
        fails(() -> DocumentUploads.contentType(null, null));
    }

    @Test
    void theFileNameLosesFoldersAndControlCharacters() {
        assertEquals("cert.pdf", DocumentUploads.fileName("C:\\Users\\me\\cert.pdf"));
        assertEquals("cert.pdf", DocumentUploads.fileName("../../etc/cert.pdf"));
        assertEquals("ab.pdf", DocumentUploads.fileName("  a\nb.pdf "));
        assertEquals(200, DocumentUploads.fileName("x".repeat(500) + ".pdf").length());
        fails(() -> DocumentUploads.fileName(null));
        fails(() -> DocumentUploads.fileName("/"));
    }

    @Test
    void theWalletBodyCarriesTheFileAsBase64() {
        byte[] file = "%PDF-1.4 hello".getBytes(StandardCharsets.UTF_8);
        String json = new String(DocumentUploads.walletBody("director id", "me \"a\".pdf", null, file), StandardCharsets.UTF_8);
        Map<String, Object> m = JsonReader.readObject(json);
        assertEquals("DIRECTOR_ID", m.get("type"));
        assertEquals("me \"a\".pdf", m.get("fileName"));
        assertEquals("application/pdf", m.get("contentType"));
        assertEquals(Base64.getEncoder().encodeToString(file), m.get("contentBase64"));
    }

    @Test
    void emptyAndOversizedFilesAreRefused() {
        fails(() -> DocumentUploads.walletBody("other", "a.pdf", null, new byte[0]));
        fails(() -> DocumentUploads.walletBody("other", "a.pdf", null, null));
        fails(() -> DocumentUploads.walletBody("other", "a.pdf", null, new byte[DocumentUploads.MAX_BYTES + 1]));
        DocumentUploads.walletBody("other", "a.pdf", null, new byte[DocumentUploads.MAX_BYTES]);
    }
}
