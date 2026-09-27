package com.paygate.payment.Entity;


import com.paygate.payment.Enum.PaymentStatus;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_status_history")
public class PaymentStatusHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private PaymentStatus toStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected PaymentStatusHistory() {
    }

    public PaymentStatusHistory(UUID paymentId, PaymentStatus fromStatus, PaymentStatus toStatus) {
        this.paymentId = paymentId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }

    public Long getId() { return id; }
    public UUID getPaymentId() { return paymentId; }
    public PaymentStatus getFromStatus() { return fromStatus; }
    public PaymentStatus getToStatus() { return toStatus; }
    public Instant getCreatedAt() { return createdAt; }
}
