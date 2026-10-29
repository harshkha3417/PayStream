package com.paygate.audit.controller;

import com.paygate.audit.dto.AuditLogResponse;
import com.paygate.audit.service.AuditService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * Returns full chronological audit timeline of events for an entity (payment, refund, etc.)
     * scoped to the authenticated merchant.
     */
    @GetMapping("/events")
    public ResponseEntity<List<AuditLogResponse>> getEventsForEntity(
            @RequestParam(name = "entityId") String entityId,
            @RequestHeader(name = "X-Merchant-Id") String merchantId) {
        List<AuditLogResponse> events = auditService.getEventsForEntity(entityId, merchantId);
        return ResponseEntity.ok(events);
    }

    /**
     * Returns paginated audit trail for the authenticated merchant, optionally filtered by entityType.
     */
    @GetMapping("/trail")
    public ResponseEntity<Page<AuditLogResponse>> getAuditTrail(
            @RequestHeader(name = "X-Merchant-Id") String merchantId,
            @RequestParam(name = "entityType", required = false) String entityType,
            @PageableDefault(size = 50, sort = "recordedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<AuditLogResponse> trail = auditService.getAuditTrail(merchantId, entityType, pageable);
        return ResponseEntity.ok(trail);
    }
}
