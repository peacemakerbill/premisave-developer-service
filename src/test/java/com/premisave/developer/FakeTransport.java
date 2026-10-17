package com.premisave.developer;

import com.premisave.developer.client.HttpTransport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A scripted stand in for the providers: answers by the start of the address and remembers every call. */
public final class FakeTransport implements HttpTransport {

    public record Call(String method, String url, Map<String, String> headers, Map<String, String> form) {}

    public final List<Call> calls = new ArrayList<>();
    public final Map<String, Reply> replies = new LinkedHashMap<>();
    public boolean down;

    public FakeTransport on(String urlStart, int status, String body) {
        replies.put(urlStart, new Reply(status, body));
        return this;
    }

    @Override
    public Reply postForm(String url, Map<String, String> headers, Map<String, String> form) throws IOException {
        calls.add(new Call("POST", url, headers, form));
        return answer(url);
    }

    @Override
    public Reply get(String url, Map<String, String> headers) throws IOException {
        calls.add(new Call("GET", url, headers, Map.of()));
        return answer(url);
    }

    private Reply answer(String url) throws IOException {
        if (down) throw new IOException("connection refused");
        for (Map.Entry<String, Reply> e : replies.entrySet()) {
            if (url.startsWith(e.getKey())) return e.getValue();
        }
        return new Reply(404, "{}");
    }

    public Call last() {
        return calls.get(calls.size() - 1);
    }
}
