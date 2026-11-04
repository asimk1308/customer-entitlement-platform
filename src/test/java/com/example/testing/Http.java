package com.example.testing;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import tools.jackson.databind.json.JsonMapper;

/** A small client for black-box calls, so that every test goes through real HTTP. */
public final class Http {
    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String base;

    public Http(String base) {
        this.base = base;
    }

    public String url() {
        return base;
    }

    public Reply get(String path, Map<String, String> headers) {
        return send("GET", path, HttpRequest.BodyPublishers.noBody(), null, headers);
    }

    public Reply get(String path) {
        return get(path, Map.of());
    }

    public Reply get(String path, Map<String, ?> params, Map<String, String> headers) {
        return get(path + "?" + query(params), headers);
    }

    public Reply post(String path, Object body, Map<String, String> headers) {
        return send("POST", path, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)),
                "application/json", headers);
    }

    public Reply postRaw(String path, byte[] body, Map<String, String> headers) {
        return send("POST", path, HttpRequest.BodyPublishers.ofByteArray(body), "application/json", headers);
    }

    public Reply postForm(String path, Map<String, String> form) {
        return send("POST", path, HttpRequest.BodyPublishers.ofString(query(form)),
                "application/x-www-form-urlencoded", Map.of());
    }

    public Reply request(String method, String path, Map<String, String> headers) {
        return send(method, path, HttpRequest.BodyPublishers.noBody(), null, headers);
    }

    private Reply send(String method, String path, HttpRequest.BodyPublisher body, String contentType,
                       Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).method(method, body);
        if (contentType != null && !headers.containsKey("Content-Type")) {
            request.header("Content-Type", contentType);
        }
        headers.forEach(request::header);
        try {
            HttpResponse<String> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response);
        } catch (java.io.IOException | InterruptedException error) {
            throw new IllegalStateException(method + " " + path, error);
        }
    }

    public static String query(Map<String, ?> params) {
        StringJoiner joined = new StringJoiner("&");
        params.forEach((name, value) -> {
            List<Object> values = value instanceof List<?> list ? new ArrayList<>(list) : List.of(value);
            values.forEach(item -> joined.add(URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(String.valueOf(item), StandardCharsets.UTF_8)));
        });
        return joined.toString();
    }
}
