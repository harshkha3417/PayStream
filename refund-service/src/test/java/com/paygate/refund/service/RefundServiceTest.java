package com.paygate.refund.service;

import com.paygate.refund.dto.CreateRefundRequest;
import com.paygate.refund.dto.RefundResponse;
import com.paygate.refund.entity.Refund;
import com.paygate.refund.entity.RefundablePayment;
import com.paygate.refund.enums.RefundStatus;
import com.paygate.refund.exception.InvalidRefundAmountException;
import com.paygate.refund.exception.PaymentNotRefundableException;
import com.paygate.refund.repository.RefundRepository;
import com.paygate.refund.repository.RefundablePaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    @Mock
    private RefundRepository refundRepository;

    @Mock
    private RefundablePaymentRepository refundablePaymentRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private ObjectMapper objectMapper;
    private RefundService refundService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        refundService = new RefundService(refundRepository, refundablePaymentRepository, kafkaTemplate, objectMapper);
    }

    @Test
    void createRefund_fullRefund_succeeds() {
        UUID paymentId = UUID.randomUUID();
        String merchantId = "merchant-123";
        RefundablePayment payment = new RefundablePayment(paymentId, merchantId, 10000L, "INR", "SUCCEEDED");

        when(refundablePaymentRepository.findByPaymentIdAndMerchantId(paymentId, merchantId))
                .thenReturn(Optional.of(payment));
        when(refundRepository.save(any(Refund.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CreateRefundRequest request = new CreateRefundRequest(paymentId, 10000L, "INR", "Customer cancellation");
        RefundResponse response = refundService.createRefund(request, merchantId);

        assertThat(response).isNotNull();
        assertThat(response.amount()).isEqualTo(10000L);
        assertThat(response.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(payment.getStatus()).isEqualTo("REFUNDED");
        assertThat(payment.getRefundedAmountMinor()).isEqualTo(10000L);

        verify(kafkaTemplate).send(eq("refund.completed"), eq(paymentId.toString()), anyString());
    }

    @Test
    void createRefund_partialRefund_succeeds() {
        UUID paymentId = UUID.randomUUID();
        String merchantId = "merchant-123";
        RefundablePayment payment = new RefundablePayment(paymentId, merchantId, 10000L, "INR", "SUCCEEDED");

        when(refundablePaymentRepository.findByPaymentIdAndMerchantId(paymentId, merchantId))
                .thenReturn(Optional.of(payment));
        when(refundRepository.save(any(Refund.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CreateRefundRequest request = new CreateRefundRequest(paymentId, 4000L, "INR", "Item return");
        RefundResponse response = refundService.createRefund(request, merchantId);

        assertThat(response).isNotNull();
        assertThat(response.amount()).isEqualTo(4000L);
        assertThat(payment.getStatus()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(payment.getRefundedAmountMinor()).isEqualTo(4000L);
        assertThat(payment.getRemainingRefundableAmount()).isEqualTo(6000L);
    }

    @Test
    void createRefund_exceedsRemainingAmount_throwsException() {
        UUID paymentId = UUID.randomUUID();
        String merchantId = "merchant-123";
        RefundablePayment payment = new RefundablePayment(paymentId, merchantId, 10000L, "INR", "SUCCEEDED");
        payment.recordRefund(6000L); // remaining is 4000L

        when(refundablePaymentRepository.findByPaymentIdAndMerchantId(paymentId, merchantId))
                .thenReturn(Optional.of(payment));

        CreateRefundRequest request = new CreateRefundRequest(paymentId, 5000L, "INR", "Exceeds remaining");

        assertThatThrownBy(() -> refundService.createRefund(request, merchantId))
                .isInstanceOf(InvalidRefundAmountException.class)
                .hasMessageContaining("exceeds remaining refundable amount");

        verifyNoInteractions(refundRepository);
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void createRefund_currencyMismatch_throwsException() {
        UUID paymentId = UUID.randomUUID();
        String merchantId = "merchant-123";
        RefundablePayment payment = new RefundablePayment(paymentId, merchantId, 10000L, "INR", "SUCCEEDED");

        when(refundablePaymentRepository.findByPaymentIdAndMerchantId(paymentId, merchantId))
                .thenReturn(Optional.of(payment));

        CreateRefundRequest request = new CreateRefundRequest(paymentId, 5000L, "USD", "Wrong currency");

        assertThatThrownBy(() -> refundService.createRefund(request, merchantId))
                .isInstanceOf(InvalidRefundAmountException.class)
                .hasMessageContaining("Currency mismatch");
    }

    @Test
    void createRefund_paymentNotSucceeded_throwsException() {
        UUID paymentId = UUID.randomUUID();
        String merchantId = "merchant-123";
        RefundablePayment payment = new RefundablePayment(paymentId, merchantId, 10000L, "INR", "PROCESSING");

        when(refundablePaymentRepository.findByPaymentIdAndMerchantId(paymentId, merchantId))
                .thenReturn(Optional.of(payment));

        CreateRefundRequest request = new CreateRefundRequest(paymentId, 5000L, "INR", "Premature refund");

        assertThatThrownBy(() -> refundService.createRefund(request, merchantId))
                .isInstanceOf(PaymentNotRefundableException.class)
                .hasMessageContaining("only SUCCEEDED or PARTIALLY_REFUNDED");
    }
}
