package com.premisave.developer.client;

import java.io.IOException;
import java.util.Map;

/** The two HTTP calls a sign in provider needs. A seam so tests do not touch the network. */
public interface HttpTransport {

    record Reply(int status, String body) {}

    Reply postForm(String url, Map<String, String> headers, Map<String, String> form) throws IOException;

    Reply get(String url, Map<String, String> headers) throws IOException;
}
