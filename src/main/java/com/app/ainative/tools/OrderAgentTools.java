package com.app.ainative.tools;

import com.app.ainative.audit.AiGovernanceAuditService;
import com.app.ainative.domain.OrderCanonicalService;
import com.app.ainative.security.CallerTrustContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class OrderAgentTools {

    private static final BigDecimal AUTO_REFUND_LIMIT = new BigDecimal("500.00");

    private final OrderCanonicalService orderCanonicalService;
    private final AiGovernanceAuditService auditService;

    public OrderAgentTools(OrderCanonicalService orderCanonicalService,
                           AiGovernanceAuditService auditService) {
        this.orderCanonicalService = orderCanonicalService;
        this.auditService = auditService;
    }

    @Tool(description = "Solicita ou executa o reembolso de um pedido validado após análise da política aplicável.")
    public ToolExecutionResult requestOrderRefund(
            @ToolParam(description = "Código identificador do pedido, ex: ORD-1001") String orderId,
            @ToolParam(description = "Valor numérico do reembolso solicitado") BigDecimal amount,
            @ToolParam(description = "Justificativa baseada na política recuperada no RAG") String reason) {

        // 1. Recupera a identidade real diretamente do ScopedValue (Java 25), nunca do LLM!
        CallerTrustContext trustCtx = CallerTrustContext.requireCurrent();

        // 2. Checa permissão explícita na aplicação
        if (!trustCtx.hasPermission("orders:refund:write")) {
            auditService.recordToolAttempt(trustCtx, "requestOrderRefund", orderId, "DENIED_PERMISSION");
            return new ToolExecutionResult("REJECTED", "Usuário não possui permissão para solicitar reembolsos.");
        }

        // 3. O LLM nunca é a fonte da verdade: valida na API Canônica se o pedido pertence ao tenant
        if (!orderCanonicalService.belongsToTenant(orderId, trustCtx.tenantId())) {
            auditService.recordToolAttempt(trustCtx, "requestOrderRefund", orderId, "DENIED_CROSS_TENANT_OR_NOT_FOUND");
            return new ToolExecutionResult("REJECTED", "Pedido não encontrado para a organização atual.");
        }

        // 4. BOUNDED AUTONOMY + HUMAN-IN-THE-LOOP: Valores acima de R$ 500 exigem aprovação humana via SQS
        if (amount.compareTo(AUTO_REFUND_LIMIT) > 0) {
            auditService.enqueueHumanApproval(trustCtx, orderId, amount, reason);
            auditService.recordToolAttempt(trustCtx, "requestOrderRefund", orderId, "AWAITING_HUMAN_APPROVAL");
            return new ToolExecutionResult(
                    "AWAITING_HUMAN_APPROVAL",
                    "O valor de R$ " + amount + " excede o limite de autonomia do agente (R$ "
                            + AUTO_REFUND_LIMIT + "). Enviado para fila de aprovação de um supervisor.");
        }

        // 5. Dentro da alçada: executa transação determinística no serviço de domínio
        orderCanonicalService.processRefund(trustCtx.tenantId(), orderId, amount, reason);
        auditService.recordToolAttempt(trustCtx, "requestOrderRefund", orderId, "EXECUTED_AUTOMATICALLY");

        return new ToolExecutionResult("EXECUTED", "Reembolso de R$ " + amount + " efetivado com sucesso.");
    }

    public record ToolExecutionResult(String status, String details) {}
}
