package com.paygate.refund.repository;

import com.paygate.refund.entity.RefundablePayment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface RefundablePaymentRepository extends JpaRepository<RefundablePayment, UUID> {
    Optional<RefundablePayment> findByPaymentIdAndMerchantId(UUID paymentId, String merchantId);
}
