package com.paygate.payment.repository;

import com.paygate.payment.Entity.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByIdAndMerchantId(UUID id, String merchantId);

    Page<Payment> findByMerchantId(String merchantId, Pageable pageable);
}
