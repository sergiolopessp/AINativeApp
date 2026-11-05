package com.app.ainative.orchestration;

import com.app.ainative.audit.AiGovernanceAuditService;
import com.app.ainative.security.CallerTrustContext;
import com.app.ainative.tools.OrderAgentTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SupportAiOrchestrator {

    // Modelos padrão do Amazon Bedrock para Roteamento Inteligente (FinOps)
    public static final String DEFAULT_BEDROCK_FAST_MODEL = "us.amazon.nova-micro-v1:0";
    public static final String DEFAULT_BEDROCK_CAPABLE_AGENT_MODEL = "us.anthropic.claude-sonnet-4-5-20250929-v1:0";

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final OrderAgentTools orderAgentTools;
    private final AiGovernanceAuditService auditService;
    private final String bedrockFastModel;
    private final String bedrockCapableAgentModel;

    public SupportAiOrchestrator(ChatClient.Builder chatClientBuilder,
                                 VectorStore vectorStore,
                                 OrderAgentTools orderAgentTools,
                                 AiGovernanceAuditService auditService) {
        this(chatClientBuilder, vectorStore, orderAgentTools, auditService,
                DEFAULT_BEDROCK_FAST_MODEL, DEFAULT_BEDROCK_CAPABLE_AGENT_MODEL);
    }

    @Autowired
    public SupportAiOrchestrator(ChatClient.Builder chatClientBuilder,
                                 VectorStore vectorStore,
                                 OrderAgentTools orderAgentTools,
                                 AiGovernanceAuditService auditService,
                                 @Value("${app.ai.models.fast:" + DEFAULT_BEDROCK_FAST_MODEL + "}") String bedrockFastModel,
                                 @Value("${app.ai.models.capable:" + DEFAULT_BEDROCK_CAPABLE_AGENT_MODEL + "}") String bedrockCapableAgentModel) {
        this.chatClient = chatClientBuilder
                .defaultSystem("""
                        Você é um assistente operacional da aplicação conectado via Amazon Bedrock.
                        Use estritamente os documentos fornecidos no CONTEXTO AUTORIZADO DA ORGANIZAÇÃO.
                        Regras de preenchimento da resposta estruturada:
                        - Em citedPolicyDocuments, inclua os códigos DocID (ex: POL-2026-BR) utilizados do contexto autorizado.
                        - Em bedrockModelUsed, retorne exatamente o nome informado em MODELO SELECIONADO.
                        - Se a solicitação for apenas uma dúvida/consulta informativa (sem pedido de reembolso/estorno), responda com base na política do contexto, defina resolutionStatus como "ANSWERED_INFO_ONLY" e evaluatedAmount como 0.00.
                        - Se a solicitação pedir reembolso ou estorno financeiro, invoque obrigatoriamente a ferramenta requestOrderRefund e preencha resolutionStatus exatamente com o campo status retornado pela ferramenta (EXECUTED, AWAITING_HUMAN_APPROVAL ou REJECTED).
                        """)
                .build();
        this.vectorStore = vectorStore;
        this.orderAgentTools = orderAgentTools;
        this.auditService = auditService;
        this.bedrockFastModel = bedrockFastModel;
        this.bedrockCapableAgentModel = bedrockCapableAgentModel;
    }

    /**
     * Schema de saída estruturada validado pelo Spring AI (.entity()).
     */
    public record AgentSupportResponse(
            String orderId,
            String resolutionStatus,
            BigDecimal evaluatedAmount,
            String bedrockModelUsed,
            String customerMessage,
            List<String> citedPolicyDocuments
    ) {}

    public AgentSupportResponse handleSupportTicket(CallerTrustContext trustContext, String userPrompt) {
        // Vincula o contexto de segurança ao ScopedValue do Java 25 durante toda a execução
        return ScopedValue.where(CallerTrustContext.CURRENT, trustContext).call(() -> {

            // 1. GUARDRAIL DE ENTRADA
            validateInputPrompt(userPrompt, trustContext);

            // 2. RAG COM PRE-FILTERING NO VECTOR STORE (Isolamento estrito de Tenant e Status Ativo)
            var filterBuilder = new FilterExpressionBuilder();
            var securityFilter = filterBuilder.and(
                    filterBuilder.eq("tenantId", trustContext.tenantId()),
                    filterBuilder.eq("status", "ACTIVE")
            ).build();

            List<Document> authorizedDocs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(userPrompt)
                            .topK(4)
                            .similarityThreshold(0.20)
                            .filterExpression(securityFilter)
                            .build()
            );

            String ragContext = authorizedDocs.stream()
                    .map(doc -> "[DocID: " + doc.getMetadata().get("docId") + "] " + doc.getText())
                    .collect(Collectors.joining("\n---\n"));

            // 3. ROTEAMENTO DE MODELO NO AMAZON BEDROCK (Model Selection como Decisão Arquitetural)
            String selectedBedrockModel = selectBedrockModelForTask(userPrompt, authorizedDocs);

            // 4. ORQUESTRAÇÃO SPRING AI + BEDROCK CONVERSE API: Contexto RAG + Tools + Structured Output
            AgentSupportResponse response = chatClient.prompt()
                    .options(ToolCallingChatOptions.builder()
                            .model(selectedBedrockModel)
                            .temperature(0.1)
                            .build())
                    .user(u -> u.text("""
                            CONTEXTO AUTORIZADO DA ORGANIZAÇÃO:
                            {ragContext}
                            
                            MODELO SELECIONADO: {selectedModel}
                            
                            SOLICITAÇÃO DO USUÁRIO:
                            {userPrompt}
                            """)
                            .param("ragContext", ragContext)
                            .param("selectedModel", selectedBedrockModel)
                            .param("userPrompt", userPrompt))
                    .tools(orderAgentTools)
                    .call()
                    .entity(AgentSupportResponse.class);

            // 5. AUDITORIA FINAL DO WORKFLOW (Incluindo qual modelo do Bedrock foi utilizado)
            auditService.recordWorkflowCompletion(
                    trustContext, response.orderId(), response.resolutionStatus() + " [" + selectedBedrockModel + "]");
            return response;
        });
    }

    private String selectBedrockModelForTask(String prompt, List<Document> docs) {
        boolean requiresFinancialAction = prompt.toLowerCase().contains("reembolso")
                || prompt.toLowerCase().contains("estorno");
        return (requiresFinancialAction || docs.size() > 2)
                ? bedrockCapableAgentModel
                : bedrockFastModel;
    }

    private void validateInputPrompt(String prompt, CallerTrustContext ctx) {
        if (prompt == null || prompt.isBlank() || prompt.length() > 2000) {
            throw new IllegalArgumentException("Prompt fora dos limites operacionais permitidos.");
        }
        String lower = prompt.toLowerCase();
        if (lower.contains("ignore previous instructions") || lower.contains("ignore todas as instruções")) {
            auditService.recordWorkflowCompletion(ctx, "N/A", "BLOCKED_PROMPT_INJECTION");
            throw new SecurityException("Violação detectada pelo Guardrail de Entrada.");
        }
    }
}
