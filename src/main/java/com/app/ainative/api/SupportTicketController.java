package com.app.ainative.api;

import com.app.ainative.orchestration.SupportAiOrchestrator;
import com.app.ainative.orchestration.SupportAiOrchestrator.AgentSupportResponse;
import com.app.ainative.security.CallerTrustContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/support")
public class SupportTicketController {

    private final SupportAiOrchestrator orchestrator;

    public SupportTicketController(SupportAiOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    public record SupportTicketRequest(String prompt) {}

    @PostMapping("/tickets")
    public ResponseEntity<AgentSupportResponse> handleTicket(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-Permissions", defaultValue = "orders:refund:write") String permissionsHeader,
            @RequestBody SupportTicketRequest request) {

        Set<String> permissions = Arrays.stream(permissionsHeader.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        var trustContext = new CallerTrustContext(tenantId, userId, permissions);
        AgentSupportResponse response = orchestrator.handleSupportTicket(trustContext, request.prompt());
        return ResponseEntity.ok(response);
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<String> handleSecurityException(SecurityException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getMessage());
    }
}
