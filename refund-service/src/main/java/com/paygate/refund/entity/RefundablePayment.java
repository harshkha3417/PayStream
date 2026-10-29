package com.paygate.refund.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refundable_payments")
public class RefundablePayment {

    @Id
    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "refunded_amount_minor", nullable = false)
    private long refundedAmountMinor;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public RefundablePayment() {}

    public RefundablePayment(UUID paymentId, String merchantId, long amountMinor, String currency, String status) {
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.refundedAmountMinor = 0;
        this.status = status;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public long getRemainingRefundableAmount() {
        return amountMinor - refundedAmountMinor;
    }

    public boolean canRefund(long amount) {
        return amount > 0 && (refundedAmountMinor + amount) <= amountMinor;
    }

    public void recordRefund(long amount) {
        this.refundedAmountMinor += amount;
        this.status = (this.refundedAmountMinor >= this.amountMinor) ? "REFUNDED" : "PARTIALLY_REFUNDED";
        this.updatedAt = Instant.now();
    }

    public UUID getPaymentId() { return paymentId; }
    public String getMerchantId() { return merchantId; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public long getRefundedAmountMinor() { return refundedAmountMinor; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }
}
