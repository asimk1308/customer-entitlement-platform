package com.example.testing;

import java.net.http.HttpResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public record Reply(int status, HttpResponse<String> raw) {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public String text() {
        return raw.body();
    }

    public JsonNode json() {
        return JSON.readTree(raw.body());
    }

    /** Null if the header is absent. */
    public String header(String name) {
        return raw.headers().firstValue(name).orElse(null);
    }

    public String contentType() {
        return header("Content-Type");
    }
}
