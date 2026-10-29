package com.paygate.refund.consumer;

import com.paygate.refund.dto.PaymentEventEnvelope;
import com.paygate.refund.entity.ProcessedEvent;
import com.paygate.refund.repository.ProcessedEventRepository;
import com.paygate.refund.service.RefundService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final RefundService refundService;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    public PaymentEventConsumer(
            RefundService refundService,
            ProcessedEventRepository processedEventRepository,
            ObjectMapper objectMapper) {
        this.refundService = refundService;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = {"payment.status-changed", "payment.created"}, groupId = "refund-service", concurrency = "${KAFKA_LISTENER_CONCURRENCY:4}")
    @Transactional
    public void onPaymentEvent(String payload) {
        PaymentEventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(payload, PaymentEventEnvelope.class);
        } catch (Exception e) {
            log.error("Failed to parse PaymentEventEnvelope: {}", payload, e);
            throw new IllegalArgumentException("Malformed payment event payload", e);
        }

        // Consumer Deduplication (processed_events table per architecture.md §8)
        if (processedEventRepository.existsById(envelope.eventId())) {
            log.info("Duplicate event {} ignored for paymentId={}", envelope.eventId(), envelope.paymentId());
            return;
        }

        Map<String, Object> data = envelope.data();
        String toStatus = data.get("to") != null ? data.get("to").toString() :
                          data.get("toStatus") != null ? data.get("toStatus").toString() :
                          data.get("status") != null ? data.get("status").toString() : null;

        if ("SUCCEEDED".equalsIgnoreCase(toStatus)) {
            long amount = 0L;
            if (data.get("amount") instanceof Number num) {
                amount = num.longValue();
            } else if (data.get("amount") != null) {
                amount = Long.parseLong(data.get("amount").toString());
            }

            String currency = data.get("currency") != null ? data.get("currency").toString() : "INR";

            log.info("Registering refundable payment {} for merchant {} with amount {} {}",
                    envelope.paymentId(), envelope.merchantId(), amount, currency);

            refundService.registerRefundablePayment(
                    envelope.paymentId(),
                    envelope.merchantId(),
                    amount,
                    currency,
                    "SUCCEEDED"
            );
        }

        processedEventRepository.save(new ProcessedEvent(envelope.eventId()));
    }
}
