package com.app.ainative.domain;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementação determinística do Domínio Canônico (Fonte da Verdade).
 * O LLM propõe ações via @Tool, mas este serviço detém o estado autoritativo.
 */
@Service
public class DefaultOrderCanonicalService implements OrderCanonicalService {

    public record OrderRecord(
            String orderId,
            String tenantId,
            BigDecimal totalAmount,
            BigDecimal refundedAmount,
            String status,
            String lastRefundReason,
            Instant updatedAt
    ) {
        public OrderRecord {
            if (orderId == null || orderId.isBlank()) {
                throw new IllegalArgumentException("orderId é obrigatório");
            }
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("tenantId é obrigatório");
            }
            if (totalAmount == null || totalAmount.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("totalAmount deve ser não-negativo");
            }
        }
    }

    private final Map<String, OrderRecord> orders = new ConcurrentHashMap<>();

    public DefaultOrderCanonicalService() {
        registerOrder(new OrderRecord(
                "ORD-1001", "tenant-brasil-01", new BigDecimal("350.00"),
                BigDecimal.ZERO, "DELIVERED", null, Instant.now()));
        registerOrder(new OrderRecord(
                "ORD-7700", "tenant-brasil-01", new BigDecimal("1800.00"),
                BigDecimal.ZERO, "DELIVERED", null, Instant.now()));
        registerOrder(new OrderRecord(
                "ORD-9999", "tenant-outro-02", new BigDecimal("420.00"),
                BigDecimal.ZERO, "DELIVERED", null, Instant.now()));
    }

    public void registerOrder(OrderRecord order) {
        orders.put(order.orderId(), order);
    }

    public Optional<OrderRecord> findOrder(String orderId, String tenantId) {
        return Optional.ofNullable(orders.get(orderId))
                .filter(order -> order.tenantId().equals(tenantId));
    }

    @Override
    public boolean belongsToTenant(String orderId, String tenantId) {
        OrderRecord order = orders.get(orderId);
        return order != null && order.tenantId().equals(tenantId);
    }

    @Override
    public void processRefund(String tenantId, String orderId, BigDecimal amount, String reason) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Valor de reembolso deve ser positivo.");
        }
        orders.compute(orderId, (id, existing) -> {
            if (existing == null || !existing.tenantId().equals(tenantId)) {
                throw new SecurityException("Pedido não pertence ao tenant informado: " + orderId);
            }
            if (amount.compareTo(existing.totalAmount()) > 0) {
                throw new IllegalStateException("Valor do reembolso excede o valor total do pedido.");
            }
            return new OrderRecord(
                    existing.orderId(),
                    existing.tenantId(),
                    existing.totalAmount(),
                    amount,
                    "REFUNDED",
                    reason,
                    Instant.now()
            );
        });
    }
}
