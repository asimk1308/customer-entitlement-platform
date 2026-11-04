package com.example.platform.upstream;

import com.example.platform.upstream.Models.Case;
import com.example.platform.upstream.Models.Comment;
import com.example.platform.upstream.Models.Entitlement;
import com.example.platform.upstream.Models.Issue;
import com.example.platform.upstream.Models.Usage;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import tools.jackson.databind.JsonNode;

/**
 * Turns raw upstream payloads into internal models. This is the only class that knows upstream field
 * names, so replacing a source system means changing this file and nothing else.
 */
public final class Adapters {
    static final Set<String> TIERS = Set.of("Platinum", "Gold", "Silver");
    static final Set<String> PRIORITIES = Set.of("P1", "P2", "P3", "P4");
    static final Set<String> STATUSES = Set.of("open", "in_progress", "escalated", "resolved", "closed");
    public static final String ENTITLEMENTS_CSV = "entitlements_csv";

    private Adapters() {}

    /** An upstream returned something we can't interpret. Surfaces as a 502. */
    public static class UpstreamDataError extends RuntimeException {
        public UpstreamDataError(String message) {
            super(message);
        }
    }

    public record CsvRow(String sourceId, String name, String tier, LocalDate renewal, long arrUsd) {}

    public record FeedRow(String accountId, String tier, LocalDate renewal) {}

    private static String text(JsonNode raw, String field) {
        JsonNode value = raw.get(field);
        if (value == null || !value.isString()) {
            throw new UpstreamDataError("missing or non-text field: " + field);
        }
        return value.asString();
    }

    private static Instant timestamp(String value) {
        return OffsetDateTime.parse(value).toInstant();
    }

    private static String choice(String value, Set<String> allowed, String label) {
        if (value == null || !allowed.contains(value)) {
            throw new UpstreamDataError("unknown " + label + ": " + value);
        }
        return value;
    }

    public static Comment parseComment(JsonNode raw) {
        try {
            JsonNode agent = raw.get("agent");
            JsonNode visibility = raw.get("visibility");
            return new Comment(
                    text(raw, "id"),
                    text(raw, "author"),
                    agent == null || agent.isNull() ? null : agent.asString(),
                    text(raw, "body"),
                    // Comments from before visibility existed are treated as the more restrictive option.
                    visibility == null || visibility.isNull() ? "internal" : visibility.asString(),
                    timestamp(text(raw, "at")));
        } catch (DateTimeParseException | NullPointerException error) {
            throw new UpstreamDataError("malformed comment: " + error);
        }
    }

    public static Case parseCase(JsonNode raw) {
        try {
            JsonNode version = raw.get("version");
            JsonNode comments = raw.get("Comments");
            if (version == null || !version.isInt() || comments == null || !comments.isArray()) {
                throw new UpstreamDataError("malformed case: version or Comments");
            }
            List<Comment> parsed = new ArrayList<>();
            comments.forEach(comment -> parsed.add(parseComment(comment)));
            return new Case(
                    text(raw, "id"),
                    text(raw, "accountId"),
                    choice(text(raw, "priority"), PRIORITIES, "priority"),
                    choice(text(raw, "Status"), STATUSES, "status"),
                    timestamp(text(raw, "openedAt")),
                    text(raw, "subject"),
                    version.asInt(),
                    List.copyOf(parsed));
        } catch (DateTimeParseException | NullPointerException error) {
            throw new UpstreamDataError("malformed case: " + error);
        }
    }

    public static List<CsvRow> parseEntitlementCsv(String text) {
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            List<CsvRow> rows = new ArrayList<>();
            for (CSVRecord row : parser) {
                rows.add(new CsvRow(
                        row.get("account_id"),
                        row.get("name"),
                        choice(row.get("support_tier"), TIERS, "tier"),
                        LocalDate.parse(row.get("contract_end")),
                        Long.parseLong(row.get("arr_usd"))));
            }
            return rows;
        } catch (IOException | UncheckedIOException | IllegalArgumentException | IllegalStateException
                | DateTimeParseException error) {
            // Only the type: this message is logged, and a parser's message can quote the ARR value.
            throw new UpstreamDataError("malformed entitlement CSV: " + error.getClass().getSimpleName());
        }
    }

    public static List<FeedRow> parseEntitlementXml(String text) {
        try {
            Document document = secureXmlFactory().newDocumentBuilder().parse(new InputSource(new StringReader(text)));
            NodeList accounts = document.getElementsByTagName("account");
            List<FeedRow> rows = new ArrayList<>();
            for (int i = 0; i < accounts.getLength(); i++) {
                Element account = (Element) accounts.item(i);
                if (!account.hasAttribute("id")) {
                    throw new UpstreamDataError("account without an id");
                }
                rows.add(new FeedRow(
                        account.getAttribute("id"),
                        choice(childText(account, "tier"), TIERS, "tier"),
                        LocalDate.parse(childText(account, "renewal"))));
            }
            return rows;
        } catch (ParserConfigurationException | SAXException | IOException | DateTimeParseException | NullPointerException error) {
            throw new UpstreamDataError("malformed entitlement XML: " + error);
        }
    }

    /** Refuses any DOCTYPE, which rules out entity-expansion and external-entity payloads. */
    private static DocumentBuilderFactory secureXmlFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static String childText(Element parent, String tag) {
        NodeList found = parent.getElementsByTagName(tag);
        return found.getLength() == 0 ? null : found.item(0).getTextContent();
    }

    /**
     * Combines both sources. The XML feed wins on tier and renewal because it is the fresher of the
     * two; only the CSV export carries name and ARR.
     *
     * <p>IDs are never guessed: {@code ACC-77} padded would be {@code ACC-077}, not {@code ACC-007}. A
     * CSV ID maps through the crosswalk, or is reported as a data quality issue.
     *
     * @param crosswalk keyed by source ID, for the CSV source
     */
    public static Map<String, Entitlement> mergeEntitlements(List<CsvRow> csvRows, List<FeedRow> xmlRows,
                                                             Map<String, String> crosswalk) {
        Map<String, FeedRow> feed = new LinkedHashMap<>();
        xmlRows.forEach(row -> feed.put(row.accountId(), row));
        Map<String, Entitlement> merged = new LinkedHashMap<>();

        for (CsvRow row : csvRows) {
            String mapped = crosswalk.get(row.sourceId());
            String accountId = mapped != null ? mapped : row.sourceId();
            List<Issue> issues = new ArrayList<>();
            if (mapped == null && !feed.containsKey(accountId)) {
                issues.add(new Issue("unmapped_source_id",
                        "CSV ID " + row.sourceId() + " is not in the crosswalk or the XML feed."));
            }
            merged.put(accountId, applyFeed(row, accountId, issues, feed.get(accountId)));
        }
        feed.forEach((accountId, row) -> {
            if (!merged.containsKey(accountId)) {
                merged.put(accountId, new Entitlement(accountId, null, row.tier(), row.renewal(), null,
                        List.of(new Issue("unmapped_source_id", accountId + " is in the XML feed but not the CSV export."))));
            }
        });
        return merged;
    }

    private static Entitlement applyFeed(CsvRow row, String accountId, List<Issue> issues, FeedRow feedRow) {
        if (feedRow == null) {
            return new Entitlement(accountId, row.name(), row.tier(), row.renewal(), row.arrUsd(), List.copyOf(issues));
        }
        if (!row.tier().equals(feedRow.tier())) {
            issues.add(conflict("tier", row.tier(), feedRow.tier()));
        }
        if (!row.renewal().equals(feedRow.renewal())) {
            issues.add(conflict("renewal", row.renewal().toString(), feedRow.renewal().toString()));
        }
        return new Entitlement(accountId, row.name(), feedRow.tier(), feedRow.renewal(), row.arrUsd(), List.copyOf(issues));
    }

    private static Issue conflict(String label, String csvValue, String feedValue) {
        return new Issue("source_conflict",
                label + ": CSV has " + csvValue + ", XML feed has " + feedValue + ". Using the XML feed.");
    }

    /** Expects the row for the latest month first, as the warehouse query orders it. */
    public static Usage parseLatestUsage(JsonNode result) {
        JsonNode data = result.get("data");
        JsonNode columns = result.get("columns");
        if (data == null || columns == null || !data.isArray() || !columns.isArray()) {
            throw new UpstreamDataError("malformed usage result");
        }
        if (data.isEmpty()) {
            return null;
        }
        JsonNode first = data.get(0);
        if (first.size() != columns.size()) {
            throw new UpstreamDataError("malformed usage result: row width");
        }
        Map<String, JsonNode> row = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            row.put(columns.get(i).asString(), first.get(i));
        }
        for (String column : List.of("month", "protected_tb", "api_calls", "active_users")) {
            if (!row.containsKey(column) || row.get(column).isNull()) {
                throw new UpstreamDataError("malformed usage result: " + column);
            }
        }
        return new Usage(row.get("month").asString(), row.get("protected_tb").asDouble(),
                row.get("api_calls").asLong(), row.get("active_users").asLong());
    }
}
