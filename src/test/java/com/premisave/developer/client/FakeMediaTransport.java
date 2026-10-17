package com.premisave.developer.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Records what would have been sent to the picture store and answers with a canned reply. */
final class FakeMediaTransport implements MediaTransport {

    record Upload(String url, Map<String, String> fields, String fileField, String fileName, String contentType, byte[] file) {}
    record Form(String url, Map<String, String> form) {}

    final List<Upload> uploads = new ArrayList<>();
    final List<Form> forms = new ArrayList<>();
    Reply reply = new Reply(200, "{\"public_id\":\"premisave/developers/dev_1\",\"version\":12,"
            + "\"secure_url\":\"https://res.cloudinary.com/demo/image/upload/v12/premisave/developers/dev_1.jpg\"}");
    boolean fail;

    @Override
    public Reply postMultipart(String url, Map<String, String> fields, String fileField, String fileName, String contentType,
                               byte[] file) throws IOException {
        if (fail) throw new IOException("connection refused");
        uploads.add(new Upload(url, fields, fileField, fileName, contentType, file));
        return reply;
    }

    @Override
    public Reply postForm(String url, Map<String, String> headers, Map<String, String> form) throws IOException {
        if (fail) throw new IOException("connection refused");
        forms.add(new Form(url, form));
        return reply;
    }

    @Override
    public Reply get(String url, Map<String, String> headers) {
        throw new UnsupportedOperationException();
    }
}
