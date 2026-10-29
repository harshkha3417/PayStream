package com.paygate.ledger.entity;

import com.paygate.ledger.enums.EntryDirection;
import com.paygate.ledger.enums.LedgerAccount;
import com.paygate.ledger.enums.LedgerEntryType;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "merchant_id", nullable = false, length = 100)
    private String merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account", nullable = false, length = 50)
    private LedgerAccount account;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private EntryDirection direction;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "reference_id", length = 100)
    private String referenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 50)
    private LedgerEntryType entryType;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
        // JPA requirement
    }

    public LedgerEntry(
            UUID paymentId,
            String merchantId,
            LedgerAccount account,
            EntryDirection direction,
            long amountMinor,
            String currency,
            String referenceId,
            LedgerEntryType entryType,
            String description) {
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.account = account;
        this.direction = direction;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.referenceId = referenceId;
        this.entryType = entryType;
        this.description = description;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getPaymentId() { return paymentId; }
    public String getMerchantId() { return merchantId; }
    public LedgerAccount getAccount() { return account; }
    public EntryDirection getDirection() { return direction; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public String getReferenceId() { return referenceId; }
    public LedgerEntryType getEntryType() { return entryType; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
}
