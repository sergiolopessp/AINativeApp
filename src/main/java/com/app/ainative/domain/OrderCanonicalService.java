package com.app.ainative.domain;

import java.math.BigDecimal;

/**
 * Contrato do Modelo de Domínio Canônico (Fonte da Verdade) para Pedidos e Reembolsos.
 */
public interface OrderCanonicalService {

    boolean belongsToTenant(String orderId, String tenantId);

    void processRefund(String tenantId, String orderId, BigDecimal amount, String reason);
}
