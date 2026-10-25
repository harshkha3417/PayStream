package com.paygate.auth.entity;

import com.paygate.auth.enums.MerchantStatus;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchants")
public class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    /** BCrypt hash of the merchant's API key. The plaintext key is shown to the
     *  merchant exactly once, at creation time, and never stored or logged. */
    @Column(name = "api_key_hash", nullable = false, length = 100)
    private String apiKeyHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MerchantStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Merchant() {
    }

    public Merchant(String name, String apiKeyHash) {
        this.name = name;
        this.apiKeyHash = apiKeyHash;
        this.status = MerchantStatus.ACTIVE;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getApiKeyHash() { return apiKeyHash; }
    public MerchantStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    public boolean isActive() { return status == MerchantStatus.ACTIVE; }
}