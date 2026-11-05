package com.app.ainative.security;

import java.lang.ScopedValue;
import java.util.Set;

/**
 * Contexto de Segurança propagado via ScopedValue (Java 25 - JEP 506).
 * Imutável, seguro para Virtual Threads e inacessível para manipulação pelo LLM.
 */
public record CallerTrustContext(
        String tenantId,
        String userId,
        Set<String> permissions
) {
    // ScopedValue nativo do Java 25 para vincular o contexto à execução atual
    public static final ScopedValue<CallerTrustContext> CURRENT = ScopedValue.newInstance();

    public CallerTrustContext {
        if (tenantId == null || tenantId.isBlank()) {
            throw new SecurityException("Tenant ID é obrigatório no contexto de confiança.");
        }
        if (userId == null || userId.isBlank()) {
            throw new SecurityException("User ID é obrigatório no contexto de confiança.");
        }
        permissions = Set.copyOf(permissions);
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    public static CallerTrustContext requireCurrent() {
        return CURRENT.orElseThrow(() ->
                new SecurityException("Tentativa de executar operação de IA fora de um escopo autenticado!"));
    }
}
