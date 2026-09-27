package com.paygate.payment.Entity;

import com.paygate.payment.Enum.PaymentMethod;
import com.paygate.payment.Enum.PaymentStatus;
import com.paygate.payment.Exception.InvalidStatusTransitionException;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    /** Money is stored in minor units (paise/cents) as a long. Never use double for money. */
    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(length = 255)
    private String description;

    /** Optimistic locking: Hibernate adds "WHERE version = ?" to every UPDATE. */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
        // required by JPA
    }

    public Payment(String merchantId, long amountMinor, String currency,
                   PaymentMethod method, String description) {
        this.merchantId = merchantId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.method = method;
        this.description = description;
        this.status = PaymentStatus.CREATED;
    }

    public void transitionTo(PaymentStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidStatusTransitionException(status, next);
        }
        this.status = next;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getMerchantId() { return merchantId; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public PaymentMethod getMethod() { return method; }
    public PaymentStatus getStatus() { return status; }
    public String getDescription() { return description; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
