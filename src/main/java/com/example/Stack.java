package com.example;

import com.example.mocks.idp.IdpApp;
import com.example.mocks.receiver.ReceiverApp;
import com.example.mocks.upstreams.UpstreamsApp;
import com.example.platform.PlatformApp;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * {@code make run}: the API, the mock upstreams, the identity provider and the webhook receiver in
 * one JVM, each its own Spring context on its own port, so that the only link between them is HTTP.
 */
public final class Stack {
    private Stack() {}

    public static void main(String[] args) {
        start(UpstreamsApp.class, port("UPSTREAM_PORT", 9001),
                "warehouse.path=" + env("WAREHOUSE_PATH", "var/warehouse.db"));
        start(ReceiverApp.class, port("RECEIVER_PORT", 9002));
        int idp = port("IDP_PORT", 9100);
        start(IdpApp.class, idp, "idp.issuer=" + env("IDP_ISSUER", "http://localhost:" + idp));
        int api = port("API_PORT", 8000);
        start(PlatformApp.class, api);
        System.out.printf("API on http://localhost:%d  (spec at /openapi.yaml). Ctrl-C to stop.%n", api);
    }

    private static void start(Class<?> app, int port, String... properties) {
        new SpringApplicationBuilder(app)
                .properties("server.port=" + port)
                .properties(properties)
                .run();
    }

    private static int port(String name, int fallback) {
        return Integer.parseInt(env(name, String.valueOf(fallback)));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }
}
