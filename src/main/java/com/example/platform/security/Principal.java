package com.example.platform.security;

import java.util.Set;

/**
 * @param agent set if an agent is acting on the subject's behalf
 * @param accounts null means no per-account restriction
 */
public record Principal(String subject, Set<String> scopes, String agent, Set<String> accounts) {
    public static final String ATTRIBUTE = Principal.class.getName();

    public boolean canSeeAccount(String accountId) {
        return accounts == null || accounts.contains(accountId);
    }

    public boolean has(String scope) {
        return scopes.contains(scope);
    }
}
