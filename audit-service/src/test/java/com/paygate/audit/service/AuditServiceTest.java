package com.paygate.audit.service;

import com.paygate.audit.dto.AuditLogResponse;
import com.paygate.audit.dto.PaymentEventEnvelope;
import com.paygate.audit.entity.AuditLog;
import com.paygate.audit.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    private AuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new AuditService(auditLogRepository);
    }

    @Test
    void recordEvent_newDomainEvent_savesAuditLog() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String merchantId = "merchant-123";
        PaymentEventEnvelope envelope = new PaymentEventEnvelope(
                eventId,
                "payment.created",
                Instant.now(),
                paymentId,
                merchantId,
                Map.of("amount", 10000L, "currency", "INR")
        );
        String rawPayload = "{\"eventId\":\"" + eventId + "\"}";

        when(auditLogRepository.existsByEventId(eventId)).thenReturn(false);
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        AuditLog saved = auditService.recordEvent(envelope, rawPayload);

        assertThat(saved).isNotNull();
        assertThat(saved.getEventId()).isEqualTo(eventId);
        assertThat(saved.getTopic()).isEqualTo("payment.created");
        assertThat(saved.getEntityType()).isEqualTo("PAYMENT");
        assertThat(saved.getEntityId()).isEqualTo(paymentId.toString());
        assertThat(saved.getMerchantId()).isEqualTo(merchantId);
        assertThat(saved.getActor()).isEqualTo("PAYMENT_SERVICE");
        assertThat(saved.getAction()).isEqualTo("PAYMENT_CREATED");
        assertThat(saved.getPayload()).isEqualTo(rawPayload);

        verify(auditLogRepository).save(any(AuditLog.class));
    }

    @Test
    void recordEvent_duplicateEvent_isIgnored() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        PaymentEventEnvelope envelope = new PaymentEventEnvelope(
                eventId,
                "payment.created",
                Instant.now(),
                paymentId,
                "merchant-123",
                Map.of()
        );

        when(auditLogRepository.existsByEventId(eventId)).thenReturn(true);

        AuditLog result = auditService.recordEvent(envelope, "{}");

        assertThat(result).isNull();
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    void getEventsForEntity_returnsOrderedTimeline() {
        String entityId = UUID.randomUUID().toString();
        String merchantId = "merchant-123";

        AuditLog log1 = new AuditLog(UUID.randomUUID(), "payment.created", "PAYMENT", entityId, merchantId, "PAYMENT_SERVICE", "PAYMENT_CREATED", "{}", Instant.now().minusSeconds(10));
        AuditLog log2 = new AuditLog(UUID.randomUUID(), "payment.status-changed", "PAYMENT", entityId, merchantId, "PAYMENT_SERVICE", "PAYMENT_SUCCEEDED", "{}", Instant.now());

        when(auditLogRepository.findByEntityIdAndMerchantIdOrderByOccurredAtAsc(entityId, merchantId))
                .thenReturn(List.of(log1, log2));

        List<AuditLogResponse> events = auditService.getEventsForEntity(entityId, merchantId);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).action()).isEqualTo("PAYMENT_CREATED");
        assertThat(events.get(1).action()).isEqualTo("PAYMENT_SUCCEEDED");
    }

    @Test
    void getAuditTrail_returnsPagedTrail() {
        String merchantId = "merchant-123";
        PageRequest pageable = PageRequest.of(0, 10);
        AuditLog log1 = new AuditLog(UUID.randomUUID(), "payment.created", "PAYMENT", "id-1", merchantId, "PAYMENT_SERVICE", "PAYMENT_CREATED", "{}", Instant.now());

        when(auditLogRepository.findByMerchantIdOrderByRecordedAtDesc(merchantId, pageable))
                .thenReturn(new PageImpl<>(List.of(log1)));

        Page<AuditLogResponse> page = auditService.getAuditTrail(merchantId, null, pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().getFirst().merchantId()).isEqualTo(merchantId);
    }
}
