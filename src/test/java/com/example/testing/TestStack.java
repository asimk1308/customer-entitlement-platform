package com.example.testing;

import com.example.mocks.idp.IdpApp;
import com.example.mocks.idp.IdpController;
import com.example.mocks.receiver.ReceiverApp;
import com.example.mocks.receiver.ReceiverController;
import com.example.mocks.upstreams.UpstreamsApp;
import com.example.mocks.upstreams.UpstreamsState;
import com.example.platform.PlatformApp;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The real API against the real mocks, in one JVM on random ports. The mocks are shared, because they
 * can be reset. The API is built per test, so that its caches and circuit breakers start clean.
 */
public final class TestStack {
    public static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
    public static final MutableClock CLOCK = new MutableClock(NOW);

    private static final Path WAREHOUSE;
    private static final ConfigurableApplicationContext UPSTREAMS;
    private static final ConfigurableApplicationContext RECEIVER;
    private static final ConfigurableApplicationContext IDP;

    public static final Http IDP_HTTP;
    public static final Http UPSTREAM_HTTP;
    public static final Http RECEIVER_HTTP;

    static {
        try {
            WAREHOUSE = Files.createTempDirectory("warehouse").resolve("warehouse.db");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        UPSTREAMS = start(UpstreamsApp.class, "warehouse.path=" + WAREHOUSE);
        RECEIVER = start(ReceiverApp.class);
        // The issuer is fixed rather than the random port, which the tokens don't depend on.
        IDP = start(IdpApp.class, "idp.issuer=http://idp.test");
        IDP_HTTP = new Http("http://127.0.0.1:" + port(IDP));
        UPSTREAM_HTTP = new Http("http://127.0.0.1:" + port(UPSTREAMS));
        RECEIVER_HTTP = new Http("http://127.0.0.1:" + port(RECEIVER));
    }

    private TestStack() {}

    private static ConfigurableApplicationContext start(Class<?> app, String... properties) {
        ApplicationContextInitializer<ConfigurableApplicationContext> clock =
                context -> context.getBeanFactory().registerSingleton("clock", (Clock) CLOCK);
        // Arguments rather than builder properties, which are defaults that application.properties overrides.
        // Loopback only. On macOS a random port on all addresses can be one another program holds on
        // 127.0.0.1, and requests to localhost then reach that program instead of the test's server.
        List<String> arguments = new ArrayList<>(List.of("--server.port=0", "--server.address=127.0.0.1"));
        for (String property : properties) {
            arguments.add("--" + property);
        }
        return new SpringApplicationBuilder(app).initializers(clock).run(arguments.toArray(String[]::new));
    }

    private static int port(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    public static UpstreamsState upstreams() {
        return UPSTREAMS.getBean(UpstreamsState.class);
    }

    public static ReceiverController receiver() {
        return RECEIVER.getBean(ReceiverController.class);
    }

    public static IdpController idp() {
        return IDP.getBean(IdpController.class);
    }

    public static String receiverHook() {
        return RECEIVER_HTTP.url() + "/webhooks/case-escalated";
    }

    public static void resetMocks() {
        CLOCK.set(NOW);
        upstreams().reset();
        receiver().reset();
    }

    public static Api startApi(Path db, List<String> overrides) {
        List<String> properties = new ArrayList<>(List.of(
                "platform.db-path=" + db,
                "platform.upstream-url=" + UPSTREAM_HTTP.url(),
                "platform.idp-url=" + IDP_HTTP.url(),
                "platform.issuer=http://idp.test",
                "platform.webhook-allowed-hosts=" + RECEIVER_HTTP.url().replace("http://", ""),
                // Every request refreshes, so faults are seen immediately.
                "platform.entitlement-ttl=0s",
                // Tests run delivery cycles themselves.
                "platform.outbox-poll=0s"));
        // Fast retries, and a breaker that needs a deliberate burst of failures to open.
        for (String upstream : List.of("cases", "entitlements", "usage")) {
            properties.addAll(List.of(
                    "platform." + upstream + ".attempt-timeout=300ms",
                    "platform." + upstream + ".max-attempts=2",
                    "platform." + upstream + ".deadline=700ms",
                    "platform." + upstream + ".backoff-base=1ms",
                    "platform." + upstream + ".backoff-cap=2ms",
                    "platform." + upstream + ".failure-threshold=50"));
        }
        // A repeated argument would be joined with a comma rather than replaced.
        Map<String, String> byName = new LinkedHashMap<>();
        Stream.concat(properties.stream(), overrides.stream()).forEach(property -> {
            int equals = property.indexOf('=');
            byName.put(property.substring(0, equals), property);
        });
        ConfigurableApplicationContext context = start(PlatformApp.class, byName.values().toArray(String[]::new));
        return new Api(context, new Http("http://127.0.0.1:" + port(context)));
    }

    /** {@code support} and so on, for the audience the API accepts. */
    public static String token(String persona) {
        return token(persona, IdpController.API_AUDIENCE, 3600);
    }

    public static String token(String persona, String audience, int ttlSeconds) {
        JsonNode reply = IDP_HTTP.post("/dev/token",
                Map.of("persona", persona, "audience", audience, "ttlSeconds", ttlSeconds), Map.of()).json();
        return reply.get("access_token").asString();
    }

    public static Map<String, String> auth(String persona) {
        return Map.of("Authorization", "Bearer " + token(persona));
    }

    public static Map<String, String> auth() {
        return auth("support");
    }
}
