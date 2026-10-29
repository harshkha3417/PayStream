package com.paygate.refund.service;

import com.paygate.refund.dto.CreateRefundRequest;
import com.paygate.refund.dto.PaymentEventEnvelope;
import com.paygate.refund.dto.RefundResponse;
import com.paygate.refund.entity.Refund;
import com.paygate.refund.entity.RefundablePayment;
import com.paygate.refund.enums.RefundStatus;
import com.paygate.refund.exception.InvalidRefundAmountException;
import com.paygate.refund.exception.PaymentNotRefundableException;
import com.paygate.refund.exception.RefundNotFoundException;
import com.paygate.refund.repository.RefundRepository;
import com.paygate.refund.repository.RefundablePaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);
    public static final String TOPIC_REFUND_COMPLETED = "refund.completed";
    public static final String TOPIC_REFUND_REQUESTED = "refund.requested";

    private final RefundRepository refundRepository;
    private final RefundablePaymentRepository refundablePaymentRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public RefundService(
            RefundRepository refundRepository,
            RefundablePaymentRepository refundablePaymentRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.refundRepository = refundRepository;
        this.refundablePaymentRepository = refundablePaymentRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RefundResponse createRefund(CreateRefundRequest request, String merchantId) {
        log.info("Processing refund request for paymentId={}, merchantId={}, amount={}",
                request.paymentId(), merchantId, request.amount());

        RefundablePayment payment = refundablePaymentRepository
                .findByPaymentIdAndMerchantId(request.paymentId(), merchantId)
                .orElseThrow(() -> new PaymentNotRefundableException(request.paymentId(),
                        "Payment not found for merchant or has not succeeded yet"));

        if (!"SUCCEEDED".equalsIgnoreCase(payment.getStatus()) &&
            !"PARTIALLY_REFUNDED".equalsIgnoreCase(payment.getStatus())) {
            throw new PaymentNotRefundableException(request.paymentId(),
                    "Payment status is " + payment.getStatus() + ", only SUCCEEDED or PARTIALLY_REFUNDED payments can be refunded");
        }

        if (!payment.getCurrency().equalsIgnoreCase(request.currency())) {
            throw new InvalidRefundAmountException("Currency mismatch: payment currency is " +
                    payment.getCurrency() + ", refund request currency is " + request.currency());
        }

        if (request.amount() <= 0) {
            throw new InvalidRefundAmountException("Refund amount must be strictly positive");
        }

        if (!payment.canRefund(request.amount())) {
            throw new InvalidRefundAmountException("Refund amount " + request.amount() +
                    " exceeds remaining refundable amount " + payment.getRemainingRefundableAmount());
        }

        // Apply refund to local read model
        payment.recordRefund(request.amount());
        refundablePaymentRepository.save(payment);

        UUID refundId = UUID.randomUUID();
        Refund refund = new Refund(
                refundId,
                payment.getPaymentId(),
                merchantId,
                request.amount(),
                payment.getCurrency(),
                request.reason()
        );
        refund.setStatus(RefundStatus.COMPLETED);
        Refund saved = refundRepository.save(refund);

        // Publish refund.completed event to Kafka
        publishRefundEvent(TOPIC_REFUND_COMPLETED, saved, payment.getStatus(), payment.getRemainingRefundableAmount());

        log.info("Refund {} completed for paymentId={}. New payment state={}",
                refundId, payment.getPaymentId(), payment.getStatus());

        return RefundResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public RefundResponse getRefund(UUID id, String merchantId) {
        return refundRepository.findByIdAndMerchantId(id, merchantId)
                .map(RefundResponse::fromEntity)
                .orElseThrow(() -> new RefundNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<RefundResponse> getRefundsByPayment(UUID paymentId, String merchantId) {
        return refundRepository.findByPaymentIdAndMerchantId(paymentId, merchantId)
                .stream()
                .map(RefundResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RefundResponse> listRefunds(String merchantId) {
        return refundRepository.findByMerchantId(merchantId)
                .stream()
                .map(RefundResponse::fromEntity)
                .toList();
    }

    @Transactional
    public void registerRefundablePayment(UUID paymentId, String merchantId, long amountMinor, String currency, String status) {
        refundablePaymentRepository.findById(paymentId).ifPresentOrElse(
                existing -> {
                    log.info("Updating existing refundable payment {}", paymentId);
                    existing.setStatus(status);
                    refundablePaymentRepository.save(existing);
                },
                () -> {
                    log.info("Registering new refundable payment {} for merchant {}", paymentId, merchantId);
                    RefundablePayment payment = new RefundablePayment(paymentId, merchantId, amountMinor, currency, status);
                    refundablePaymentRepository.save(payment);
                }
        );
    }

    private void publishRefundEvent(String topic, Refund refund, String paymentStatus, long remainingAmount) {
        try {
            PaymentEventEnvelope envelope = PaymentEventEnvelope.of(
                    topic,
                    refund.getPaymentId(),
                    refund.getMerchantId(),
                    Map.of(
                            "refundId", refund.getId().toString(),
                            "amount", refund.getAmountMinor(),
                            "currency", refund.getCurrency(),
                            "reason", refund.getReason() != null ? refund.getReason() : "",
                            "status", refund.getStatus().name(),
                            "paymentStatus", paymentStatus,
                            "remainingRefundableAmount", remainingAmount
                    )
            );
            String payload = objectMapper.writeValueAsString(envelope);
            kafkaTemplate.send(topic, refund.getPaymentId().toString(), payload);
            log.info("Published {} event for paymentId={}, refundId={}", topic, refund.getPaymentId(), refund.getId());
        } catch (Exception e) {
            log.error("Failed to serialize or publish {} event for refund {}", topic, refund.getId(), e);
            throw new RuntimeException("Failed to publish refund event to Kafka", e);
        }
    }
}
