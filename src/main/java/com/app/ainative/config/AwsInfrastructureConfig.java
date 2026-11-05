package com.app.ainative.config;

import com.app.ainative.orchestration.SupportAiOrchestrator;
import com.app.ainative.tools.OrderAgentTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configuração de clientes AWS SDK v2:
 * - DynamoDB e SQS roteados para o LocalStack Pro (ou AWS caso endpoint seja vazio).
 * - Amazon Bedrock (Converse + Titan Embeddings): emulado localmente quando app.ai.local-bedrock-emulator=true,
 *   ou conectado diretamente à conta AWS real do usuário quando app.ai.local-bedrock-emulator=false.
 */
@Configuration
public class AwsInfrastructureConfig {

    private static final Logger log = LoggerFactory.getLogger(AwsInfrastructureConfig.class);

    @Bean
    @ConditionalOnMissingBean
    public DynamoDbClient dynamoDbClient(
            @Value("${app.aws.endpoint:http://localhost:4566}") String endpoint,
            @Value("${app.aws.region:us-east-1}") String region) {
        var builder = DynamoDbClient.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test", "test")));
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    public SqsClient sqsClient(
            @Value("${app.aws.endpoint:http://localhost:4566}") String endpoint,
            @Value("${app.aws.region:us-east-1}") String region) {
        var builder = SqsClient.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test", "test")));
        }
        return builder.build();
    }

    /**
     * Quando o emulador local está ativo (app.ai.local-bedrock-emulator=true), sobrescreve o endpoint do Bedrock
     * para o LocalStack Pro. Quando false, o Spring AI cria o BedrockRuntimeClient oficial conectando direto na AWS!
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "app.ai.local-bedrock-emulator", havingValue = "true", matchIfMissing = true)
    public BedrockRuntimeClient localStackBedrockRuntimeClient(
            @Value("${app.aws.endpoint:http://localhost:4566}") String endpoint,
            @Value("${app.aws.region:us-east-1}") String region) {
        return BedrockRuntimeClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "app.ai.local-bedrock-emulator", havingValue = "true", matchIfMissing = true)
    public BedrockRuntimeAsyncClient localStackBedrockRuntimeAsyncClient(
            @Value("${app.aws.endpoint:http://localhost:4566}") String endpoint,
            @Value("${app.aws.region:us-east-1}") String region) {
        return BedrockRuntimeAsyncClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .build();
    }

    /**
     * Emulador local determinístico do Titan Embeddings V2 (1024 dimensões) para execução 100% local.
     * Desativado automaticamente quando app.ai.local-bedrock-emulator=false (usando o Titan Embeddings real da AWS).
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.ai.local-bedrock-emulator", havingValue = "true", matchIfMissing = true)
    public EmbeddingModel localTitanEmbeddingModel() {
        return new AbstractEmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                List<Embedding> embeddings = new ArrayList<>();
                List<String> instructions = request.getInstructions();
                for (int i = 0; i < instructions.size(); i++) {
                    embeddings.add(new Embedding(deterministicUnitVector(instructions.get(i), 1024), i));
                }
                return new EmbeddingResponse(embeddings);
            }

            @Override
            public float[] embed(Document document) {
                return deterministicUnitVector(document.getText(), 1024);
            }

            private float[] deterministicUnitVector(String text, int dimensions) {
                float[] vector = new float[dimensions];
                byte[] bytes = (text == null ? "" : text.toLowerCase()).getBytes(StandardCharsets.UTF_8);
                for (int i = 0; i < dimensions; i++) {
                    int b = bytes.length > 0 ? bytes[i % bytes.length] : 1;
                    vector[i] = (float) Math.sin(i * 0.17 + b);
                }
                double norm = 0.0;
                for (float v : vector) {
                    norm += v * v;
                }
                norm = Math.sqrt(norm);
                if (norm > 0) {
                    for (int i = 0; i < dimensions; i++) {
                        vector[i] /= (float) norm;
                    }
                }
                return vector;
            }
        };
    }

    /**
     * Emulador local do Bedrock Converse ChatModel conectado às @Tools reais (OrderAgentTools).
     * Desativado automaticamente quando app.ai.local-bedrock-emulator=false (usando o Bedrock Converse real da AWS).
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.ai.local-bedrock-emulator", havingValue = "true", matchIfMissing = true)
    public ChatModel localBedrockConverseChatModel(OrderAgentTools orderAgentTools) {
        return (Prompt prompt) -> {
            String requestedModel = prompt.getOptions() != null && prompt.getOptions().getModel() != null
                    ? prompt.getOptions().getModel()
                    : SupportAiOrchestrator.DEFAULT_BEDROCK_CAPABLE_AGENT_MODEL;

            String fullText = prompt.getContents();
            String userSection = fullText.contains("SOLICITAÇÃO DO USUÁRIO:")
                    ? fullText.substring(fullText.indexOf("SOLICITAÇÃO DO USUÁRIO:"))
                    : fullText;

            String orderId = extractOrderId(userSection);
            BigDecimal amount = extractAmount(userSection);
            boolean asksForRefund = userSection.toLowerCase().contains("reembolso")
                    || userSection.toLowerCase().contains("estorno");

            if (asksForRefund) {
                var toolResult = orderAgentTools.requestOrderRefund(
                        orderId, amount, "Solicitação avaliada conforme política POL-2026-BR recuperada no RAG");
                String json = """
                        {
                          "orderId": "%s",
                          "resolutionStatus": "%s",
                          "evaluatedAmount": %s,
                          "bedrockModelUsed": "%s",
                          "customerMessage": "%s",
                          "citedPolicyDocuments": ["POL-2026-BR"]
                        }
                        """.formatted(orderId, toolResult.status(), amount.toPlainString(), requestedModel, toolResult.details());
                return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
            }

            String json = """
                    {
                      "orderId": "%s",
                      "resolutionStatus": "ANSWERED_INFO_ONLY",
                      "evaluatedAmount": 0.00,
                      "bedrockModelUsed": "%s",
                      "customerMessage": "Consulta respondida com base nos documentos autorizados do tenant.",
                      "citedPolicyDocuments": ["POL-2026-BR"]
                    }
                    """.formatted(orderId, requestedModel);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
        };
    }

    @Bean
    @ConditionalOnProperty(name = "app.aws.auto-init", havingValue = "true", matchIfMissing = true)
    public ApplicationRunner initializeLocalStackAndVectorStore(
            DynamoDbClient dynamoDb,
            SqsClient sqs,
            VectorStore vectorStore,
            @Value("${app.aws.audit-table:AppAiAuditTrail}") String auditTable) {
        return args -> {
            try {
                dynamoDb.createTable(CreateTableRequest.builder()
                        .tableName(auditTable)
                        .keySchema(KeySchemaElement.builder().attributeName("orderId").keyType(KeyType.HASH).build())
                        .attributeDefinitions(AttributeDefinition.builder().attributeName("orderId").attributeType(ScalarAttributeType.S).build())
                        .billingMode(BillingMode.PAY_PER_REQUEST)
                        .build());
                log.info("Tabela DynamoDB '{}' criada no LocalStack Pro.", auditTable);
            } catch (ResourceInUseException ignored) {
                log.info("Tabela DynamoDB '{}' já existe no LocalStack Pro.", auditTable);
            } catch (Exception e) {
                log.warn("Não foi possível inicializar a tabela DynamoDB no LocalStack Pro: {}", e.getMessage());
            }

            try {
                String queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                        .queueName("agent-hitl-approval-queue")
                        .build()).queueUrl();
                log.info("Fila SQS de Human-in-the-Loop pronta no LocalStack Pro: {}", queueUrl);
            } catch (Exception e) {
                log.warn("Não foi possível inicializar a fila SQS no LocalStack Pro: {}", e.getMessage());
            }

            try {
                String docUuid = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
                try {
                    vectorStore.delete(List.of(docUuid));
                } catch (Exception ignored) {
                }
                vectorStore.add(List.of(
                        new Document(
                                docUuid,
                                "Política de Reembolso 2026 (POL-2026-BR): Pedidos com avaria ou defeito têm direito a estorno. Reembolsos até R$ 500,00 são aprovados automaticamente; acima de R$ 500,00 exigem aprovação humana.",
                                Map.of("docId", "POL-2026-BR", "tenantId", "tenant-brasil-01", "status", "ACTIVE")
                        )
                ));
                log.info("Documento de política inicial 'POL-2026-BR' indexado no PgVector.");
            } catch (Exception e) {
                log.warn("Não foi possível indexar documento inicial no PgVector: {}", e.getMessage());
            }
        };
    }

    private static String extractOrderId(String text) {
        Matcher m = Pattern.compile("(ORD-\\d+)").matcher(text);
        return m.find() ? m.group(1) : "ORD-1001";
    }

    private static BigDecimal extractAmount(String text) {
        String withoutOrderIds = text.replaceAll("ORD-\\d+", "");
        Matcher m = Pattern.compile("(?:R\\$\\s*)?(\\d+(?:[.,]\\d{1,2})?)").matcher(withoutOrderIds);
        if (m.find()) {
            return new BigDecimal(m.group(1).replace(",", "."));
        }
        return new BigDecimal("100.00");
    }
}
