package com.app.ainative.audit;

import com.app.ainative.security.CallerTrustContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Serviço de Governança, Trilha de Auditoria (DynamoDB) e Fila de Aprovação Humana / HITL (SQS).
 */
@Service
public class AiGovernanceAuditService {

    private final DynamoDbClient dynamoDb;
    private final SqsClient sqs;
    private final String auditTableName;
    private final String approvalQueueUrl;

    public AiGovernanceAuditService(
            DynamoDbClient dynamoDb,
            SqsClient sqs,
            @Value("${app.aws.audit-table:AppAiAuditTrail}") String auditTableName,
            @Value("${app.aws.approval-queue-url:http://localhost:4566/000000000000/agent-hitl-approval-queue}") String approvalQueueUrl) {
        this.dynamoDb = dynamoDb;
        this.sqs = sqs;
        this.auditTableName = auditTableName;
        this.approvalQueueUrl = approvalQueueUrl;
    }

    public void recordToolAttempt(CallerTrustContext trustCtx, String toolName, String orderId, String status) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("orderId", AttributeValue.fromS(orderId != null ? orderId : "N/A"));
        item.put("tenantId", AttributeValue.fromS(trustCtx.tenantId()));
        item.put("userId", AttributeValue.fromS(trustCtx.userId()));
        item.put("toolName", AttributeValue.fromS(toolName));
        item.put("status", AttributeValue.fromS(status));
        item.put("timestamp", AttributeValue.fromS(Instant.now().toString()));

        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(auditTableName)
                .item(item)
                .build());
    }

    public void enqueueHumanApproval(CallerTrustContext trustCtx, String orderId, BigDecimal amount, String reason) {
        String payload = """
                {
                  "orderId": "%s",
                  "tenantId": "%s",
                  "userId": "%s",
                  "amount": "%s",
                  "reason": "%s",
                  "requestedAt": "%s"
                }
                """.formatted(
                orderId,
                trustCtx.tenantId(),
                trustCtx.userId(),
                amount.toPlainString(),
                reason,
                Instant.now().toString()
        );

        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(approvalQueueUrl)
                .messageBody(payload)
                .build());
    }

    public void recordWorkflowCompletion(CallerTrustContext trustCtx, String orderId, String status) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("orderId", AttributeValue.fromS(orderId != null ? orderId : "N/A"));
        item.put("tenantId", AttributeValue.fromS(trustCtx.tenantId()));
        item.put("userId", AttributeValue.fromS(trustCtx.userId()));
        item.put("status", AttributeValue.fromS(status));
        item.put("timestamp", AttributeValue.fromS(Instant.now().toString()));

        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(auditTableName)
                .item(item)
                .build());
    }
}
