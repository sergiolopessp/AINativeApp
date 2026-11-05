package com.app.ainative.rag;

import com.app.ainative.security.CallerTrustContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Pipeline Vetorial Coeso e Reutilizável (Seção 3.1 a 3.3 do artigo):
 * Ingest -> Normalize/Sanitize -> Index no VectorStore com metadados obrigatórios de isolamento e proveniência.
 */
@Service
public class PolicyIngestionService {

    private final VectorStore vectorStore;

    public PolicyIngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public record PolicyDocumentInput(
            String docId,
            String rawContent,
            String version
    ) {
        public PolicyDocumentInput {
            if (docId == null || docId.isBlank()) {
                throw new IllegalArgumentException("docId é obrigatório para proveniência no RAG.");
            }
            if (rawContent == null || rawContent.isBlank()) {
                throw new IllegalArgumentException("Conteúdo do documento não pode ser vazio.");
            }
        }
    }

    public void ingestPolicyDocument(PolicyDocumentInput input) {
        CallerTrustContext trustCtx = CallerTrustContext.requireCurrent();
        if (!trustCtx.hasPermission("policies:ingest:write")) {
            throw new SecurityException("Usuário não possui permissão para indexar documentos no RAG.");
        }

        String sanitizedText = normalizeAndSanitize(input.rawContent());

        Document vectorDoc = new Document(
                input.docId(),
                sanitizedText,
                Map.of(
                        "docId", input.docId(),
                        "tenantId", trustCtx.tenantId(),
                        "status", "ACTIVE",
                        "version", input.version() != null ? input.version() : "v1",
                        "ingestedBy", trustCtx.userId(),
                        "ingestedAt", Instant.now().toString()
                )
        );

        vectorStore.add(List.of(vectorDoc));
    }

    private String normalizeAndSanitize(String text) {
        return text.trim()
                .replaceAll("(?i)\\b\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}\\b", "[CPF_REDACTED]");
    }
}
