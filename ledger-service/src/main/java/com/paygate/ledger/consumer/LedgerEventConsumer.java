package com.paygate.ledger.consumer;

import com.paygate.ledger.dto.PaymentEventEnvelope;
import com.paygate.ledger.entity.ProcessedEvent;
import com.paygate.ledger.repository.ProcessedEventRepository;
import com.paygate.ledger.service.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@Component
public class LedgerEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(LedgerEventConsumer.class);

    private final LedgerService ledgerService;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    public LedgerEventConsumer(
            LedgerService ledgerService,
            ProcessedEventRepository processedEventRepository,
            ObjectMapper objectMapper) {
        this.ledgerService = ledgerService;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = {"payment.status-changed", "refund.completed"}, groupId = "ledger-service", concurrency = "${KAFKA_LISTENER_CONCURRENCY:4}")
    @Transactional
    public void onEvent(String payload) {
        PaymentEventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(payload, PaymentEventEnvelope.class);
        } catch (Exception e) {
            log.error("Failed to parse PaymentEventEnvelope: {}", payload, e);
            throw new IllegalArgumentException("Malformed event payload", e);
        }

        // Deduplicate using processed_events table per architecture.md §8
        if (processedEventRepository.existsById(envelope.eventId())) {
            log.info("Duplicate event {} ignored by ledger-service for paymentId={}",
                    envelope.eventId(), envelope.paymentId());
            return;
        }

        Map<String, Object> data = envelope.data();
        if (data == null) {
            log.warn("Received event {} with null data, skipping", envelope.eventId());
            processedEventRepository.save(new ProcessedEvent(envelope.eventId()));
            return;
        }

        if ("payment.status-changed".equals(envelope.type())) {
            String toStatus = (String) data.getOrDefault("to", data.get("status"));
            if ("SUCCEEDED".equalsIgnoreCase(toStatus)) {
                Object amtObj = data.get("amount");
                Object curObj = data.get("currency");
                if (amtObj != null && curObj != null) {
                    long amountMinor = ((Number) amtObj).longValue();
                    String currency = curObj.toString();
                    ledgerService.recordPaymentSettlement(
                            envelope.paymentId(), envelope.merchantId(), amountMinor, currency);
                } else {
                    log.warn("payment.status-changed SUCCEEDED event missing amount or currency: {}", envelope);
                }
            }
        } else if ("refund.completed".equals(envelope.type())) {
            Object amtObj = data.get("amount");
            Object curObj = data.get("currency");
            Object refIdObj = data.get("refundId");
            if (amtObj != null && curObj != null) {
                long amountMinor = ((Number) amtObj).longValue();
                String currency = curObj.toString();
                String refundId = refIdObj != null ? refIdObj.toString() : null;
                ledgerService.recordRefund(
                        envelope.paymentId(), envelope.merchantId(), refundId, amountMinor, currency);
            } else {
                log.warn("refund.completed event missing amount or currency: {}", envelope);
            }
        }

        processedEventRepository.save(new ProcessedEvent(envelope.eventId()));
    }
}
