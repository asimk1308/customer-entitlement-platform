package com.example.mocks.upstreams;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.sqlite.SQLiteConfig;

/**
 * The warehouse takes SQL over HTTP, which is how Snowflake's SQL API works, so that all three
 * upstreams can have faults injected the same way.
 */
@RestController
public class UpstreamsController {
    private static final Set<String> UPSTREAMS = Set.of("cases", "entitlements", "usage");

    /** An injected or simulated failure, carrying the status to return. */
    static class Failure extends RuntimeException {
        final int status;

        Failure(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final UpstreamsState state;
    private final Clock clock;
    private final Path warehousePath;
    private final ReentrantLock writes = new ReentrantLock();

    public UpstreamsController(UpstreamsState state, Clock clock,
                               @Value("${warehouse.path:var/warehouse.db}") Path warehousePath) {
        this.state = state;
        this.clock = clock;
        this.warehousePath = warehousePath;
    }

    @PostConstruct
    void createWarehouse() throws Exception {
        if (warehousePath.getParent() != null) {
            Files.createDirectories(warehousePath.getParent());
        }
        Files.deleteIfExists(warehousePath);
        try (Connection db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + warehousePath);
             Statement create = db.createStatement()) {
            create.execute("CREATE TABLE usage (account_id TEXT, month TEXT, protected_tb REAL, api_calls INTEGER, "
                    + "active_users INTEGER, PRIMARY KEY (account_id, month))");
            try (PreparedStatement insert = db.prepareStatement("INSERT INTO usage VALUES (?, ?, ?, ?, ?)")) {
                for (Object[] row : Seed.usageRows(clock.instant())) {
                    for (int i = 0; i < row.length; i++) {
                        insert.setObject(i + 1, row[i]);
                    }
                    insert.executeUpdate();
                }
            }
        }
    }

    @ExceptionHandler(Failure.class)
    ResponseEntity<Map<String, String>> onFailure(Failure failure) {
        return ResponseEntity.status(failure.status).body(Map.of("detail", failure.getMessage()));
    }

    private void applyChaos(String upstream) {
        Chaos chaos = state.chaos.get(upstream);
        if (chaos == null) {
            return;
        }
        if (chaos.latencyMs > 0) {
            try {
                Thread.sleep(chaos.latencyMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (chaos.errorStatus == null) {
            return;
        }
        if (chaos.failNext != null) {
            if (chaos.failNext <= 0) {
                return;
            }
            chaos.failNext--;
        }
        throw new Failure(chaos.errorStatus, "injected fault");
    }

    private void maybeLoseResponse() {
        Chaos chaos = state.chaos.get("cases");
        if (chaos != null && chaos.loseResponseNext > 0) {
            chaos.loseResponseNext--;
            throw new Failure(502, "injected fault: applied, but the response was lost");
        }
    }

    private Map<String, Object> caseOr404(String caseId) {
        Map<String, Object> found = state.cases.get(caseId);
        if (found == null) {
            throw new Failure(404, "no such case");
        }
        return found;
    }

    @PostMapping("/__chaos")
    ResponseEntity<?> setChaos(@RequestBody Chaos chaos) {
        if (!UPSTREAMS.contains(chaos.upstream)) {
            throw new Failure(400, "upstream must be one of " + UPSTREAMS);
        }
        state.chaos.put(chaos.upstream, chaos);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/__chaos")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clearChaos() {
        state.chaos.clear();
    }

    /** Restores the seed data, so that a Postman run can be repeated without a restart. */
    @PostMapping("/__reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reset() {
        state.reset();
    }

    /** Lets black-box tests arrange a scenario. {@code latestCommentBody} rewrites the last comment. */
    @PostMapping("/__cases/{caseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SuppressWarnings("unchecked")
    void changeCase(@PathVariable String caseId, @RequestBody Map<String, Object> changes) {
        Map<String, Object> target = caseOr404(caseId);
        changes.forEach((key, value) -> {
            if (key.equals("latestCommentBody")) {
                List<Map<String, Object>> comments = (List<Map<String, Object>>) target.get("Comments");
                comments.getLast().put("body", value);
            } else {
                target.put(key, value);
            }
        });
    }

    @GetMapping("/cases")
    Map<String, Object> listCases() {
        applyChaos("cases");
        return Map.of("records", new ArrayList<>(state.cases.values()));
    }

    @GetMapping("/cases/{caseId}")
    Map<String, Object> getCase(@PathVariable String caseId) {
        applyChaos("cases");
        return caseOr404(caseId);
    }

    record CommentIn(String externalId, String author, String agent, String body, String visibility) {}

    @PostMapping("/cases/{caseId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @SuppressWarnings("unchecked")
    Object addComment(@PathVariable String caseId, @RequestBody CommentIn comment) {
        applyChaos("cases");
        writes.lock();
        try {
            if (state.writeResults.containsKey(comment.externalId())) {
                maybeLoseResponse();
                return state.writeResults.get(comment.externalId());
            }
            Map<String, Object> target = caseOr404(caseId);
            List<Map<String, Object>> comments = (List<Map<String, Object>>) target.get("Comments");
            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("id", caseId + "-c" + (comments.size() + 1));
            stored.put("author", comment.author());
            stored.put("agent", comment.agent());
            stored.put("body", comment.body());
            stored.put("visibility", comment.visibility() == null ? "internal" : comment.visibility());
            stored.put("at", Seed.iso(clock.instant()));
            comments.add(stored);
            target.put("version", (Integer) target.get("version") + 1);
            state.writeResults.put(comment.externalId(), stored);
            maybeLoseResponse();
            return stored;
        } finally {
            writes.unlock();
        }
    }

    record TransitionIn(String externalId, String to, int expectedVersion, String reason, String justification) {}

    @PostMapping("/cases/{caseId}/transition")
    Object transition(@PathVariable String caseId, @RequestBody TransitionIn change) {
        applyChaos("cases");
        writes.lock();
        try {
            if (state.writeResults.containsKey(change.externalId())) {
                maybeLoseResponse();
                return state.writeResults.get(change.externalId());
            }
            Runnable hook = state.beforeTransition;
            if (hook != null) {
                hook.run();
            }
            Map<String, Object> target = caseOr404(caseId);
            if ((Integer) target.get("version") != change.expectedVersion()) {
                throw new Failure(409, "version mismatch");
            }
            target.put("Status", change.to());
            target.put("version", (Integer) target.get("version") + 1);
            state.writeResults.put(change.externalId(), new LinkedHashMap<>(target));
            maybeLoseResponse();
            return target;
        } finally {
            writes.unlock();
        }
    }

    @GetMapping(value = "/entitlements/export.csv", produces = "text/csv")
    String entitlementCsv() {
        applyChaos("entitlements");
        return Seed.entitlementCsv(clock.instant());
    }

    @GetMapping(value = "/entitlements/feed.xml", produces = MediaType.APPLICATION_XML_VALUE)
    String entitlementXml() {
        applyChaos("entitlements");
        return Seed.entitlementXml(clock.instant());
    }

    record StatementIn(String statement, List<Object> bindings) {}

    @PostMapping("/warehouse/statements")
    Map<String, Object> runStatement(@RequestBody StatementIn statement) {
        applyChaos("usage");
        // Read-only at the connection level, so this can't change data whatever SQL it is sent.
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        try (Connection db = config.createConnection("jdbc:sqlite:" + warehousePath);
             PreparedStatement query = db.prepareStatement(statement.statement())) {
            List<Object> bindings = statement.bindings() == null ? List.of() : statement.bindings();
            for (int i = 0; i < bindings.size(); i++) {
                query.setObject(i + 1, bindings.get(i));
            }
            if (!query.execute()) {
                return Map.of("columns", List.of(), "data", List.of());
            }
            try (ResultSet rows = query.getResultSet()) {
                int width = rows.getMetaData().getColumnCount();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= width; i++) {
                    columns.add(rows.getMetaData().getColumnLabel(i));
                }
                List<List<Object>> data = new ArrayList<>();
                while (rows.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= width; i++) {
                        row.add(rows.getObject(i));
                    }
                    data.add(row);
                }
                return Map.of("columns", columns, "data", data);
            }
        } catch (SQLException error) {
            throw new Failure(400, String.valueOf(error.getMessage()));
        }
    }
}
