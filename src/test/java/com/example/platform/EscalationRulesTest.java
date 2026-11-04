package com.example.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.platform.domain.EscalationRules;
import com.example.platform.web.ApiProblem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class EscalationRulesTest {
    @ParameterizedTest
    @ValueSource(strings = {"open", "in_progress"})
    void activeStatesCanEscalate(String status) {
        assertThatCode(() -> EscalationRules.checkState(status)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"escalated, case_already_escalated", "resolved, case_not_escalatable", "closed, case_not_escalatable"})
    void otherStatesCannot(String status, String code) {
        assertThatThrownBy(() -> EscalationRules.checkState(status))
                .isInstanceOfSatisfying(ApiProblem.class, problem -> assertThat(problem.code.wire()).isEqualTo(code));
    }

    @ParameterizedTest
    @ValueSource(strings = {"P1", "P2"})
    void urgentCasesNeverDependOnTier(String priority) {
        assertThat(EscalationRules.needsTier(priority)).isFalse();
        assertThatCode(() -> {
            EscalationRules.checkEligibility(priority, null);
            EscalationRules.checkEligibility(priority, "Silver");
        }).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"P3", "P4"})
    void lowerPrioritiesDependOnTier(String priority) {
        assertThat(EscalationRules.needsTier(priority)).isTrue();
        assertThatCode(() -> EscalationRules.checkEligibility(priority, "Platinum")).doesNotThrowAnyException();
        assertThatCode(() -> EscalationRules.checkEligibility(priority, "Gold")).doesNotThrowAnyException();
        assertThatThrownBy(() -> EscalationRules.checkEligibility(priority, "Silver")).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> EscalationRules.checkEligibility(priority, null)).isInstanceOf(ApiProblem.class);
    }

    @Test
    void anUnknownStatusIsNotEscalatable() {
        assertThatThrownBy(() -> EscalationRules.checkState("archived")).isInstanceOf(ApiProblem.class);
    }
}
