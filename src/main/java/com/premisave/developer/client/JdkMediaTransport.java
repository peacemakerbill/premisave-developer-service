package com.premisave.developer.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

/** Uploads through the JDK client, with a longer timeout than a plain call because a picture is being sent. */
public class JdkMediaTransport extends JdkHttpTransport implements MediaTransport {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public Reply postMultipart(String url, Map<String, String> fields, String fileField, String fileName,
                               String contentType, byte[] file) throws IOException {
        byte[] random = new byte[12];
        RANDOM.nextBytes(random);
        String boundary = "premisave" + HexFormat.of().formatHex(random);
        byte[] body = multipartBody(boundary, fields, fileField, fileName, contentType, file);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return send(request);
    }

    /** The request body. Quotes and line breaks are removed from names so a value cannot start another part. */
    static byte[] multipartBody(String boundary, Map<String, String> fields, String fileField, String fileName,
                                String contentType, byte[] file) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(file.length + 1024);
        for (Map.Entry<String, String> f : fields.entrySet()) {
            write(out, "--" + boundary + "\r\n");
            write(out, "Content-Disposition: form-data; name=\"" + safe(f.getKey()) + "\"\r\n\r\n");
            write(out, f.getValue() + "\r\n");
        }
        write(out, "--" + boundary + "\r\n");
        write(out, "Content-Disposition: form-data; name=\"" + safe(fileField) + "\"; filename=\"" + safe(fileName) + "\"\r\n");
        write(out, "Content-Type: " + safe(contentType) + "\r\n\r\n");
        out.writeBytes(file);
        write(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String safe(String s) {
        return s == null ? "" : s.replaceAll("[\"\\r\\n]", "");
    }
}
