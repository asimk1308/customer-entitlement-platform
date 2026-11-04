package com.example.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.platform.upstream.Adapters;
import com.example.platform.upstream.Adapters.CsvRow;
import com.example.platform.upstream.Adapters.FeedRow;
import com.example.platform.upstream.Adapters.UpstreamDataError;
import com.example.platform.upstream.Models.Case;
import com.example.platform.upstream.Models.Entitlement;
import com.example.platform.upstream.Models.Usage;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AdaptersTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate RENEWAL = LocalDate.of(2027, 1, 15);
    private static final Map<String, String> CROSSWALK = Map.of("ACC-77", "ACC-007");

    private static Map<String, Object> rawCase() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("id", "CS-10231");
        raw.put("accountId", "ACC-007");
        raw.put("priority", "P1");
        raw.put("Status", "open");
        raw.put("openedAt", "2026-10-24T08:12:00Z");
        raw.put("subject", "Backup failing on node 3");
        raw.put("version", 1);
        raw.put("Comments", List.of(Map.of("id", "c1", "author", "jdoe", "body", "Investigating", "at", "2026-10-24T09:00:00Z")));
        return raw;
    }

    private static JsonNode tree(Map<String, Object> raw) {
        return JSON.valueToTree(raw);
    }

    private static CsvRow csvRow(LocalDate renewal) {
        return new CsvRow("ACC-77", "Acme Corp", "Platinum", renewal, 480000);
    }

    private static FeedRow xmlRow() {
        return new FeedRow("ACC-007", "Platinum", RENEWAL);
    }

    @Test
    void appendixCaseIsNormalisedDespiteMixedCasing() {
        Case parsed = Adapters.parseCase(tree(rawCase()));
        assertThat(parsed.status()).isEqualTo("open");
        assertThat(parsed.comments().getFirst().body()).isEqualTo("Investigating");
        assertThat(parsed.comments().getFirst().visibility()).isEqualTo("internal");
    }

    @ParameterizedTest
    @CsvSource({"Status, reopened", "priority, P9", "openedAt, x"})
    void invalidCaseValuesAreRejected(String field, String value) {
        Map<String, Object> raw = rawCase();
        raw.put(field, value);
        assertThatThrownBy(() -> Adapters.parseCase(tree(raw))).isInstanceOf(UpstreamDataError.class);
    }

    @Test
    void caseMissingAFieldIsRejected() {
        Map<String, Object> raw = rawCase();
        raw.remove("Status");
        assertThatThrownBy(() -> Adapters.parseCase(tree(raw))).isInstanceOf(UpstreamDataError.class);
    }

    @Test
    void appendixCsvParses() {
        String text = "account_id,name,support_tier,contract_end,arr_usd\nACC-77,Acme Corp,Platinum,2027-01-15,480000\n";
        assertThat(Adapters.parseEntitlementCsv(text)).containsExactly(csvRow(RENEWAL));
    }

    @Test
    void appendixXmlParses() {
        String text = "<accounts><account id=\"ACC-007\"><tier>Platinum</tier><renewal>2027-01-15</renewal></account></accounts>";
        assertThat(Adapters.parseEntitlementXml(text)).containsExactly(xmlRow());
    }

    // Valid apart from the entity, so the only possible reason to reject it is the entity itself.
    private static final String ENTITY_BOMB = "<!DOCTYPE lolz [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;\">]>"
            + "<accounts><account id=\"ACC-1\"><tier>Platinum</tier><renewal>2027-01-15</renewal>"
            + "<note>&b;</note></account></accounts>";

    @Test
    void xmlEntityExpansionAttackIsRejected() {
        assertThatThrownBy(() -> Adapters.parseEntitlementXml(ENTITY_BOMB))
                .isInstanceOf(UpstreamDataError.class).hasMessageContaining("DOCTYPE");
    }

    /** Shows why the parser is locked down, and that the attack payload above is a real one. */
    @Test
    void theDefaultParserWouldHaveExpandedThatEntity() throws Exception {
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new StringReader(ENTITY_BOMB)));
        assertThat(document.getElementsByTagName("note").item(0).getTextContent()).isEqualTo("a".repeat(50));
    }

    @Test
    void malformedXmlIsRejected() {
        assertThatThrownBy(() -> Adapters.parseEntitlementXml("<accounts><account")).isInstanceOf(UpstreamDataError.class);
    }

    @Test
    void crosswalkJoinsAcc77ToAcc007() {
        Map<String, Entitlement> merged = Adapters.mergeEntitlements(List.of(csvRow(RENEWAL)), List.of(xmlRow()), CROSSWALK);
        assertThat(merged).containsOnlyKeys("ACC-007");
        assertThat(merged.get("ACC-007").arrUsd()).isEqualTo(480000);
        assertThat(merged.get("ACC-007").issues()).isEmpty();
    }

    @Test
    void idsAreNotGuessedWithoutACrosswalkEntry() {
        Map<String, Entitlement> merged = Adapters.mergeEntitlements(List.of(csvRow(RENEWAL)), List.of(xmlRow()), Map.of());
        assertThat(merged).containsOnlyKeys("ACC-77", "ACC-007");
        assertThat(merged.get("ACC-77").issues().getFirst().code()).isEqualTo("unmapped_source_id");
        assertThat(merged.get("ACC-007").issues().getFirst().code()).isEqualTo("unmapped_source_id");
        assertThat(merged.get("ACC-007").arrUsd()).isNull();
    }

    @Test
    void feedWinsAConflictAndTheConflictIsReported() {
        Entitlement entitlement = Adapters.mergeEntitlements(
                List.of(csvRow(LocalDate.of(2027, 3, 1))), List.of(xmlRow()), CROSSWALK).get("ACC-007");
        assertThat(entitlement.renewal()).isEqualTo(RENEWAL);
        assertThat(entitlement.issues().stream().map(issue -> issue.code())).containsExactly("source_conflict");
    }

    @Test
    void latestUsageTakesFirstRowAndAllowsNoData() {
        List<String> columns = List.of("month", "protected_tb", "api_calls", "active_users");
        Usage usage = Adapters.parseLatestUsage(JSON.valueToTree(Map.of("columns", columns,
                "data", List.of(List.of("2026-09", 412.5, 1203331, 87), List.of("2026-08", 1.0, 1, 1)))));
        assertThat(usage.month()).isEqualTo("2026-09");
        assertThat(Adapters.parseLatestUsage(JSON.valueToTree(Map.of("columns", columns, "data", List.of())))).isNull();
    }
}
