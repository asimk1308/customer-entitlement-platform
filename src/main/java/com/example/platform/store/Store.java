package com.example.platform.store;

import com.example.platform.Settings;
import com.example.platform.upstream.Models.Entitlement;
import com.example.platform.web.CorrelationFilter;
import com.example.platform.web.Times;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * SQLite access. WAL mode lets readers continue during a write, and IMMEDIATE transactions take the
 * write lock up front, so a read-then-write transaction can't fail halfway on a lock upgrade. Moving
 * to Postgres later would mostly mean changing {@code INSERT OR IGNORE} and the triggers' syntax.
 */
@Component
public class Store {
    public record Snapshot(Map<String, Entitlement> entitlements, Instant asOf) {}

    @Configuration
    static class DataSourceConfig {
        @Bean(destroyMethod = "close")
        HikariDataSource dataSource(Settings settings) throws IOException, SQLException {
            Path path = settings.dbPath();
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            HikariConfig config = new HikariConfig();
            config.setDriverClassName("org.sqlite.JDBC");
            config.setJdbcUrl("jdbc:sqlite:" + path + "?busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");
            config.setMaximumPoolSize(4);
            HikariDataSource source = new HikariDataSource(config);
            createSchema(source);
            return source;
        }

        private static void createSchema(DataSource source) throws IOException, SQLException {
            String schema = new ClassPathResource("platform-schema.sql").getContentAsString(StandardCharsets.UTF_8);
            try (Connection db = source.getConnection(); Statement statement = db.createStatement()) {
                statement.executeUpdate(schema); // The driver runs every statement in the script.
            }
        }
    }

    public final JdbcClient db;
    public final TransactionTemplate transaction;
    private final JsonMapper json;

    public Store(JdbcClient db, TransactionTemplate transaction, JsonMapper json) throws IOException {
        this.db = db;
        this.transaction = transaction;
        this.json = json;
        loadCrosswalk();
    }

    private void loadCrosswalk() throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
        try (Reader reader = new InputStreamReader(new ClassPathResource("account-crosswalk.csv").getInputStream(),
                StandardCharsets.UTF_8); CSVParser rows = CSVParser.parse(reader, format)) {
            for (CSVRecord row : rows) {
                db.sql("INSERT OR REPLACE INTO account_crosswalk VALUES (?, ?, ?, ?)")
                        .params(row.get("source"), row.get("source_id"), row.get("account_id"), row.get("note"))
                        .update();
            }
        }
    }

    public Map<String, String> crosswalk(String source) {
        Map<String, String> mapping = new HashMap<>();
        db.sql("SELECT source_id, account_id FROM account_crosswalk WHERE source = ?").param(source)
                .query((row, index) -> mapping.put(row.getString(1), row.getString(2))).list();
        return mapping;
    }

    /** Appends to the audit log. Outcomes and identifiers only, never free text. */
    public void audit(Instant at, String actor, String agent, String action, String caseId, String accountId,
                      String outcome, String detailJson) {
        db.sql("INSERT INTO audit_log (at, actor, agent, action, case_id, account_id, outcome, "
                        + "correlation_id, detail) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(Times.stored(at), actor, agent, action, caseId, accountId, outcome,
                        MDC.get(CorrelationFilter.CORRELATION_ID), detailJson)
                .update();
    }

    public void saveSnapshot(Map<String, Entitlement> entitlements, Instant asOf) {
        transaction.executeWithoutResult(status -> {
            db.sql("DELETE FROM entitlement_snapshot").update();
            entitlements.forEach((accountId, item) -> db.sql("INSERT INTO entitlement_snapshot VALUES (?, ?, ?)")
                    .params(accountId, json.writeValueAsString(item), Times.stored(asOf))
                    .update());
        });
    }

    /** Returns null if no snapshot was ever saved. */
    public Snapshot loadSnapshot() {
        record Row(String payload, String asOf) {}
        List<Row> rows = db.sql("SELECT payload, as_of FROM entitlement_snapshot")
                .query((row, index) -> new Row(row.getString(1), row.getString(2))).list();
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Entitlement> entitlements = new LinkedHashMap<>();
        for (Row row : rows) {
            Entitlement item = json.readValue(row.payload(), Entitlement.class);
            entitlements.put(item.accountId(), item);
        }
        return new Snapshot(entitlements, Instant.parse(rows.getFirst().asOf()));
    }
}
