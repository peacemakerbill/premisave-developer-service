package com.premisave.developer.client;

import java.io.IOException;
import java.util.Map;

/** The HTTP calls the picture store needs: a form post and a file upload. A seam so tests do not touch the network. */
public interface MediaTransport extends HttpTransport {

    /** Posts a multipart/form-data body: the text fields first, then one file part. */
    Reply postMultipart(String url, Map<String, String> fields, String fileField, String fileName, String contentType,
                        byte[] file) throws IOException;
}
