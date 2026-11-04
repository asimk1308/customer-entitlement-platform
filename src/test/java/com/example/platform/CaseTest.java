package com.example.platform;

import static com.example.testing.Contract.assertSchema;
import static com.example.testing.TestStack.auth;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.mocks.upstreams.Seed;
import com.example.testing.Reply;
import com.example.testing.TestStack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

class CaseTest extends ApiTest {
    private static final Map<String, Object> SAMPLE_QUESTION = Map.of(
            "priority", "P1",
            "status", List.of("open", "in_progress", "escalated"),
            "minAgeHours", 48,
            "supportTier", "Platinum",
            "renewalWithinDays", 90);

    private static Set<String> ids(Reply response) {
        Set<String> found = new HashSet<>();
        response.json().get("data").forEach(row -> found.add(row.get("id").asString()));
        return found;
    }

    private Reply list(Map<String, ?> params, Map<String, String> headers) {
        return api().http().get("/v1/cases", params, headers);
    }

    private Reply list(Map<String, ?> params) {
        return list(params, auth());
    }

    @Test
    void sampleQuestionTakesOneCallAndGetsTheHandWrittenAnswer() {
        Reply response = list(SAMPLE_QUESTION);
        assertThat(response.status()).isEqualTo(200);
        assertSchema("CaseList", response.json());
        assertThat(ids(response)).isEqualTo(Seed.SAMPLE_QUESTION_ANSWER);
    }

    /** Why the description tells callers to include `escalated` when asking what is still open. */
    @Test
    void filteringOnOpenAloneMissesTheEscalatedCase() {
        Map<String, Object> params = new HashMap<>(SAMPLE_QUESTION);
        params.put("status", List.of("open", "in_progress"));
        assertThat(ids(list(params))).containsExactlyInAnyOrder("CS-10231", "CS-10240");
    }

    @Test
    void upstreamCasingDoesNotLeak() {
        String text = api().http().get("/v1/cases/CS-10231", auth()).text();
        assertThat(text).doesNotContain("\"Status\"").doesNotContain("\"Comments\"");
    }

    @Test
    void caseDetailMatchesContractWithEtagInHeaderAndBody() {
        Reply response = api().http().get("/v1/cases/CS-10231", auth());
        JsonNode body = response.json();
        assertSchema("Case", body);
        assertThat(body.get("comments").get(0).get("body").asString()).isEqualTo("Investigating");
        assertThat(response.header("ETag")).isEqualTo(body.get("etag").asString());
        assertThat(response.header("Vary")).isEqualTo("Authorization");
    }

    @Test
    void etagChangesWhenTheCaseChanges() {
        String before = api().http().get("/v1/cases/CS-10231", auth()).header("ETag");
        bumpVersion("CS-10231");
        assertThat(api().http().get("/v1/cases/CS-10231", auth()).header("ETag")).isNotEqualTo(before);
    }

    @Test
    void unknownCaseIs404() {
        assertThat(api().http().get("/v1/cases/CS-999", auth()).status()).isEqualTo(404);
    }

    @Test
    void restrictedCallerOnlySeesCasesInTheirBook() {
        Map<String, String> headers = auth("customer-success");
        assertThat(api().http().get("/v1/cases/CS-10240", headers).status()).isEqualTo(200);
        assertThat(api().http().get("/v1/cases/CS-10231", headers).status()).isEqualTo(404);
        JsonNode rows = list(Map.of("limit", 100), headers).json().get("data");
        assertThat(rows.size()).isPositive();
        rows.forEach(row -> assertThat(row.get("accountId").asString()).isIn("ACC-012", "ACC-019"));
        assertThat(list(Map.of("accountId", "ACC-007"), headers).json().get("data")).isEmpty();
    }

    private List<List<String>> walkPages(Map<String, String> headers, Map<String, Object> params) {
        List<List<String>> pages = new ArrayList<>();
        String cursor = null;
        while (true) {
            Map<String, Object> query = new LinkedHashMap<>(params);
            if (cursor != null) {
                query.put("cursor", cursor);
            }
            JsonNode body = list(query, headers).json();
            List<String> page = new ArrayList<>();
            body.get("data").forEach(row -> page.add(row.get("id").asString()));
            pages.add(page);
            JsonNode next = body.get("page").get("nextCursor");
            cursor = next.isNull() ? null : next.asString();
            assertThat(body.get("page").get("hasMore").asBoolean()).isEqualTo(cursor != null);
            if (cursor == null) {
                return pages;
            }
        }
    }

    @Test
    void paginationReturnsEveryCaseExactlyOnce() {
        List<List<String>> pages = walkPages(auth(), Map.of("limit", 10));
        List<String> rows = pages.stream().flatMap(List::stream).toList();
        assertThat(pages.size()).isGreaterThan(1);
        assertThat(rows).doesNotHaveDuplicates().hasSize(TestStack.upstreams().cases.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"openedAt", "-openedAt", "priority", "-priority"})
    void everySortIsStableAndComplete(String sort) {
        List<String> rows = walkPages(auth(), Map.of("limit", 7, "sort", sort)).stream().flatMap(List::stream).toList();
        assertThat(rows).doesNotHaveDuplicates().hasSize(TestStack.upstreams().cases.size());
    }

    @Test
    void sortOrderIsCorrect() {
        Map<String, String> headers = auth();
        List<String> priorities = new ArrayList<>();
        list(Map.of("sort", "priority", "limit", 100), headers).json().get("data")
                .forEach(row -> priorities.add(row.get("priority").asString()));
        assertThat(priorities).isSorted();
        List<String> opened = new ArrayList<>();
        list(Map.of("sort", "-openedAt", "limit", 100), headers).json().get("data")
                .forEach(row -> opened.add(row.get("openedAt").asString()));
        assertThat(opened).isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    void aCaseInsertedDuringPagingCausesNoSkipOrRepeat() {
        Map<String, String> headers = auth();
        JsonNode first = list(Map.of("limit", 5, "sort", "-openedAt"), headers).json();
        Set<String> seen = new HashSet<>();
        first.get("data").forEach(row -> seen.add(row.get("id").asString()));
        // Newest of all, so offset pagination would shift every later row by one and repeat a row.
        Map<String, Object> newest = new HashMap<>(Seed.cases(TestStack.NOW).get("CS-10232"));
        newest.put("id", "CS-99999");
        newest.put("openedAt", "2026-11-02T11:59:00Z");
        TestStack.upstreams().cases.put("CS-99999", newest);
        Reply second = list(Map.of("limit", 5, "sort", "-openedAt",
                "cursor", first.get("page").get("nextCursor").asString()), headers);
        assertThat(ids(second)).doesNotContainAnyElementsOf(seen);
    }

    private String firstCursor(Map<String, String> headers) {
        return list(Map.of("limit", 2, "priority", "P4"), headers).json().get("page").get("nextCursor").asString();
    }

    @Test
    void tamperedCursorIsRejected() {
        Map<String, String> headers = auth();
        String cursor = firstCursor(headers);
        String forged = cursor.substring(0, 5) + (cursor.charAt(5) != 'A' ? 'A' : 'B') + cursor.substring(6);
        Reply response = list(Map.of("limit", 2, "priority", "P4", "cursor", forged), headers);
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("code").asString()).isEqualTo("invalid_cursor");
        assertSchema("Problem", response.json());
    }

    @ParameterizedTest
    @ValueSource(strings = {"!!!", "abc", "é"})
    void garbageCursorIsRejectedNotA500(String garbage) {
        assertThat(list(Map.of("cursor", garbage)).status()).isEqualTo(400);
    }

    @Test
    void longGarbageCursorIsRejected() {
        assertThat(list(Map.of("cursor", "A".repeat(200))).status()).isEqualTo(400);
    }

    @Test
    void cursorCanNotBeReusedWithDifferentFilters() {
        Map<String, String> headers = auth();
        Reply response = list(Map.of("limit", 2, "priority", "P1", "cursor", firstCursor(headers)), headers);
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("detail").asString()).contains("different filters");
    }

    private void assertInvalid(Map<String, ?> params) {
        Reply response = list(params);
        assertThat(response.status()).as(params.toString()).isEqualTo(400);
        assertSchema("Problem", response.json());
        assertThat(response.json().get("errors").size()).as(params.toString()).isPositive();
    }

    @Test
    void invalidParametersAreValidationProblems() {
        assertInvalid(Map.of("limit", 0));
        assertInvalid(Map.of("limit", 101));
        assertInvalid(Map.of("limit", "ten"));
        assertInvalid(Map.of("limit", ""));
        assertInvalid(Map.of("sort", "subject"));
        assertInvalid(Map.of("sort", ""));
        assertInvalid(Map.of("status", "reopened"));
        assertInvalid(Map.of("priority", List.of("P1", "P1")));
        assertInvalid(Map.of("minAgeHours", -1));
        assertInvalid(Map.of("minAgeHours", ""));
    }

    /** The contract repeats list parameters, so this is one invalid status and not two valid ones. */
    @Test
    void aCommaIsNotAListSeparator() {
        assertInvalid(Map.of("status", "open,closed"));
    }

    @Test
    void deprecatedParameterWorksAndAnnouncesItsRemoval() {
        Reply response = list(Map.of("account", "ACC-007"));
        response.json().get("data").forEach(row -> assertThat(row.get("accountId").asString()).isEqualTo("ACC-007"));
        assertThat(response.json().get("data").size()).isPositive();
        assertThat(response.header("Deprecation")).startsWith("@");
        assertThat(response.header("Sunset")).endsWith("GMT");
    }

    @Test
    void currentParameterHasNoDeprecationHeaders() {
        assertThat(list(Map.of("accountId", "ACC-007")).header("Deprecation")).isNull();
    }

    @Test
    void tierFilterWithEntitlementsDownAndNoSnapshotRefusesToGuess() {
        chaosError("entitlements", 500);
        Reply response = list(SAMPLE_QUESTION);
        assertThat(response.status()).isEqualTo(503);
        assertThat(response.json().get("upstream").asString()).isEqualTo("entitlements");
    }

    @Test
    void plainListWithEntitlementsDownReturnsNullTierAndIsFlagged() {
        chaosError("entitlements", 500);
        Reply response = list(Map.of("limit", 3));
        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        assertSchema("CaseList", body);
        assertThat(body.get("degraded").asBoolean()).isTrue();
        body.get("data").forEach(row -> assertThat(row.get("supportTier").isNull()).isTrue());
    }

    @Test
    void tierFilterOnASnapshotAnswersAndIsFlagged() {
        list(SAMPLE_QUESTION);
        chaosError("entitlements", 500);
        Reply response = list(SAMPLE_QUESTION);
        assertThat(ids(response)).isEqualTo(Seed.SAMPLE_QUESTION_ANSWER);
        assertThat(response.json().get("degraded").asBoolean()).isTrue();
    }

    @Test
    void caseSystemFailuresMapToTheContractsStatuses() {
        chaosError("cases", 500);
        Reply response = list(Map.of());
        assertThat(response.status()).isEqualTo(502);
        assertSchema("Problem", response.json());
        assertThat(response.json().get("upstream").asString()).isEqualTo("cases");
        chaosLatency("cases", 1000);
        assertThat(list(Map.of()).status()).isEqualTo(504);
    }
}
