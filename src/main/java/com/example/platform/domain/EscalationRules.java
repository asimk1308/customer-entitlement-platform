package com.example.platform.domain;

import com.example.platform.web.ApiProblem;
import com.example.platform.web.ApiProblem.Code;
import java.util.Set;

/** The escalation rules, with no I/O so that each rule can be tested on its own. */
public final class EscalationRules {
    private static final Set<String> ESCALATABLE = Set.of("open", "in_progress");
    private static final Set<String> ALWAYS_ELIGIBLE_PRIORITIES = Set.of("P1", "P2");
    private static final Set<String> ELIGIBLE_TIERS = Set.of("Platinum", "Gold");

    private EscalationRules() {}

    public static boolean needsTier(String priority) {
        return !ALWAYS_ELIGIBLE_PRIORITIES.contains(priority);
    }

    public static void checkState(String status) {
        if (status.equals("escalated")) {
            throw ApiProblem.of(Code.CASE_ALREADY_ESCALATED);
        }
        if (!ESCALATABLE.contains(status)) {
            throw ApiProblem.of(Code.CASE_NOT_ESCALATABLE, "A " + status + " case can't be escalated.");
        }
    }

    /** {@code tier} must be known when {@link #needsTier} is true: unknown tier is the caller's 503 to raise. */
    public static void checkEligibility(String priority, String tier) {
        if (needsTier(priority) && (tier == null || !ELIGIBLE_TIERS.contains(tier))) {
            throw ApiProblem.of(Code.ESCALATION_NOT_ELIGIBLE,
                    "P3 and P4 cases can only be escalated for Platinum or Gold customers.");
        }
    }
}
