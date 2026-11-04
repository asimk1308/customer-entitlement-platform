package com.example.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.platform.web.ApiProblem;
import com.example.platform.web.Cursors;
import com.example.platform.web.Cursors.SortKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CursorsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, Object> FILTERS = Map.of("priority", List.of("P1"), "sort", "-openedAt");
    private final Cursors cursors = new Cursors(JSON, "secret");

    @Test
    void roundTrip() {
        SortKey key = new SortKey(-100, "CS-1");
        assertThat(cursors.decode(cursors.encode(key, FILTERS), FILTERS)).isEqualTo(key);
    }

    @Test
    void filterOrderDoesNotMatter() {
        Map<String, Object> ab = new LinkedHashMap<>();
        ab.put("a", 1);
        ab.put("b", 2);
        Map<String, Object> ba = new LinkedHashMap<>();
        ba.put("b", 2);
        ba.put("a", 1);
        SortKey key = new SortKey(1, "CS-1");
        assertThat(cursors.decode(cursors.encode(key, ab), ba)).isEqualTo(key);
    }

    @Test
    void signedWithAnotherSecretIsRejected() {
        String token = new Cursors(JSON, "other").encode(new SortKey(1, "CS-1"), FILTERS);
        assertThatThrownBy(() -> cursors.decode(token, FILTERS)).isInstanceOf(ApiProblem.class);
    }

    @Test
    void differentFiltersAreRejected() {
        String token = cursors.encode(new SortKey(1, "CS-1"), FILTERS);
        assertThatThrownBy(() -> cursors.decode(token, Map.of("priority", List.of("P2"), "sort", "-openedAt")))
                .isInstanceOf(ApiProblem.class).hasMessage("invalid_cursor");
    }
}
