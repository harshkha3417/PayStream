package com.paygate.payment.repository;

import com.paygate.payment.Entity.PaymentStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentStatusHistoryRepository extends JpaRepository<PaymentStatusHistory,Long> {
}
