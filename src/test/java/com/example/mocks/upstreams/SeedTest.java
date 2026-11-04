package com.example.mocks.upstreams;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.platform.upstream.Adapters;
import com.example.platform.upstream.Models.Case;
import com.example.platform.upstream.Models.Entitlement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** The seed must answer the sample question correctly on whatever date it is run. */
class SeedTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant FIRST = Instant.parse("2026-11-02T12:00:00Z");

    private static Set<String> sampleQuestion(Instant now) {
        Map<String, Entitlement> entitlements = Adapters.mergeEntitlements(
                Adapters.parseEntitlementCsv(Seed.entitlementCsv(now)),
                Adapters.parseEntitlementXml(Seed.entitlementXml(now)),
                Map.of("ACC-77", "ACC-007"));
        Set<String> matches = new HashSet<>();
        for (Map<String, Object> raw : Seed.cases(now).values()) {
            Case parsed = Adapters.parseCase(JSON.valueToTree(raw));
            Entitlement entitlement = entitlements.get(parsed.accountId());
            long days = ChronoUnit.DAYS.between(LocalDate.ofInstant(now, ZoneOffset.UTC), entitlement.renewal());
            if (parsed.priority().equals("P1")
                    && Set.of("open", "in_progress", "escalated").contains(parsed.status())
                    && Duration.between(parsed.openedAt(), now).compareTo(Duration.ofHours(48)) > 0
                    && entitlement.tier().equals("Platinum")
                    && days >= 0 && days <= 90) {
                matches.add(parsed.id());
            }
        }
        return matches;
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "2026-11-02T12:00:00Z",
        "2027-02-01T12:00:00Z", // After the appendix's fixed renewal date.
        "2028-03-01T00:30:00Z", // Leap year, just after midnight.
        "2030-01-15T23:59:00Z", // January, so usage months cross a year.
    })
    void seedAnswersTheSampleQuestionAndHasValidMonthsOnAnyDate(String date) {
        Instant now = Instant.parse(date);
        assertThat(sampleQuestion(now)).isEqualTo(Seed.SAMPLE_QUESTION_ANSWER);

        Set<String> months = new HashSet<>();
        Seed.usageRows(now).forEach(row -> months.add((String) row[1]));
        assertThat(months).hasSize(3);
        months.forEach(month -> YearMonth.parse(month));
        assertThat(months).allMatch(month -> month.compareTo(YearMonth.from(now.atZone(ZoneOffset.UTC)).toString()) < 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dataTrapsFromTheBriefArePresent() {
        assertThat(Seed.entitlementCsv(FIRST)).contains("ACC-77,");
        assertThat(Seed.entitlementXml(FIRST)).contains("id=\"ACC-007\"");
        assertThat(Seed.cases(FIRST).get("CS-10231")).containsKey("Status");
        Set<Object> bodies = new HashSet<>();
        Seed.cases(FIRST).values().forEach(raw ->
                ((List<Map<String, Object>>) raw.get("Comments")).forEach(comment -> bodies.add(comment.get("body"))));
        assertThat(bodies).contains(Seed.INJECTION_COMMENT, Seed.PII_COMMENT);
    }

    @Test
    void thereAreEnoughCasesToNeedASecondPage() {
        assertThat(Seed.cases(FIRST).size()).isGreaterThan(25);
    }
}
