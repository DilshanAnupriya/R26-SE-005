package com.sprintiq.pms.common.web;

import com.sprintiq.pms.common.error.ApplicationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sort parsing sits on the boundary between a documented query syntax and Spring's
 * binding, and the two do not agree by default.
 *
 * <p>The API map documents {@code ?sort=field,asc|desc}. Spring binds a
 * {@code List<String>} by splitting on commas, so that arrives as two elements — which
 * an earlier version read as two field names and rejected with "Cannot sort by 'desc'".
 * These tests pin both shapes.</p>
 */
@DisplayName("Sort parser")
class SortParserTest {

    private static final Set<String> ALLOWED = Set.of("name", "createdAt", "sequenceNo");
    private static final Sort FALLBACK = Sort.by(Sort.Direction.DESC, "createdAt");

    @Test
    @DisplayName("reads the documented syntax as Spring actually binds it")
    void handlesSpringsCommaSplitBinding() {
        // What ?sort=sequenceNo,desc really produces.
        Sort sort = SortParser.parse(List.of("sequenceNo", "desc"), ALLOWED, FALLBACK);

        assertThat(sort).containsExactly(new Sort.Order(Sort.Direction.DESC, "sequenceNo"));
    }

    @Test
    @DisplayName("also reads a single token carrying both parts")
    void handlesASingleCombinedToken() {
        Sort sort = SortParser.parse(List.of("sequenceNo,desc"), ALLOWED, FALLBACK);

        assertThat(sort).containsExactly(new Sort.Order(Sort.Direction.DESC, "sequenceNo"));
    }

    @Test
    @DisplayName("defaults a property to ascending when no direction follows it")
    void bareFieldSortsAscending() {
        Sort sort = SortParser.parse(List.of("name"), ALLOWED, FALLBACK);

        assertThat(sort).containsExactly(new Sort.Order(Sort.Direction.ASC, "name"));
    }

    @Test
    @DisplayName("keeps multiple orderings, each with its own direction")
    void supportsMultipleOrderings() {
        Sort sort = SortParser.parse(List.of("name", "asc", "createdAt", "desc"), ALLOWED, FALLBACK);

        assertThat(sort).containsExactly(
                new Sort.Order(Sort.Direction.ASC, "name"),
                new Sort.Order(Sort.Direction.DESC, "createdAt"));
    }

    @Test
    @DisplayName("a direction applies only to the property it follows")
    void directionDoesNotLeakToTheNextProperty() {
        Sort sort = SortParser.parse(List.of("name", "desc", "createdAt"), ALLOWED, FALLBACK);

        assertThat(sort).containsExactly(
                new Sort.Order(Sort.Direction.DESC, "name"),
                new Sort.Order(Sort.Direction.ASC, "createdAt"));
    }

    @Test
    @DisplayName("rejects a property that is not on the allow-list")
    void refusesUnknownProperties() {
        assertThatThrownBy(() -> SortParser.parse(List.of("password"), ALLOWED, FALLBACK))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Cannot sort by 'password'")
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("a property path is refused, so it cannot reach the generated query")
    void refusesPropertyPaths() {
        assertThatThrownBy(() -> SortParser.parse(List.of("owner.email"), ALLOWED, FALLBACK))
                .isInstanceOf(ApplicationException.class);
    }

    @Test
    @DisplayName("falls back when nothing usable was supplied")
    void fallsBackOnEmptyInput() {
        assertThat(SortParser.parse(null, ALLOWED, FALLBACK)).isEqualTo(FALLBACK);
        assertThat(SortParser.parse(List.of(), ALLOWED, FALLBACK)).isEqualTo(FALLBACK);
        assertThat(SortParser.parse(List.of("  "), ALLOWED, FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    @DisplayName("a direction with no property before it is ignored, not rejected")
    void danglingDirectionIsIgnored() {
        // ?sort=desc on its own is meaningless; the endpoint's default ordering is a
        // better answer than a 422 about a string the caller concatenated wrong.
        assertThat(SortParser.parse(List.of("desc"), ALLOWED, FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    @DisplayName("direction is case-insensitive")
    void directionIgnoresCase() {
        assertThat(SortParser.parse(List.of("name", "DESC"), ALLOWED, FALLBACK))
                .containsExactly(new Sort.Order(Sort.Direction.DESC, "name"));
    }
}
