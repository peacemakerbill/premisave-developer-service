package com.premisave.developer.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClientInfoTest {

    @Test
    void describesCommonBrowsersAndSystems() {
        assertEquals("Chrome on Windows", new ClientInfo("1.1.1.1",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36").device());
        assertEquals("Edge on Windows", new ClientInfo(null,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36 Edg/126.0").device());
        assertEquals("Firefox on Linux", new ClientInfo(null, "Mozilla/5.0 (X11; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0").device());
        assertEquals("Safari on iOS", new ClientInfo(null,
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1").device());
        assertEquals("Chrome on Android", new ClientInfo(null,
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36").device());
        assertEquals("curl", new ClientInfo(null, "curl/8.5.0").device());
        assertEquals("Postman", new ClientInfo(null, "PostmanRuntime/7.39.0").device());
    }

    @Test
    void saysUnknownRatherThanGuessing() {
        assertEquals("Unknown device", ClientInfo.UNKNOWN.device());
        assertEquals("Unknown device", new ClientInfo(null, "something-odd/1.0").device());
        assertEquals("Unknown", ClientInfo.UNKNOWN.ipText());
        assertEquals("10.0.0.1", new ClientInfo("10.0.0.1", null).ipText());
    }

    @Test
    void aMissingRequestGivesUnknown() {
        assertEquals(ClientInfo.UNKNOWN, ClientInfo.of(null));
        assertNull(ClientInfo.of(null).ip());
    }
}
