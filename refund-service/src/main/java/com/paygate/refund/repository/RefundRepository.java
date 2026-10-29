package com.paygate.refund.repository;

import com.paygate.refund.entity.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, UUID> {
    List<Refund> findByPaymentIdAndMerchantId(UUID paymentId, String merchantId);
    List<Refund> findByMerchantId(String merchantId);
    Optional<Refund> findByIdAndMerchantId(UUID id, String merchantId);
}
