package com.paygate.webhook.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    @Column(nullable = false, length = 1024)
    private String url;

    @Column(nullable = false, length = 128)
    private String secret;

    @Column(name = "subscribed_events", nullable = false, length = 512)
    private String subscribedEvents;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebhookEndpoint() {
        // JPA requirement
    }

    public WebhookEndpoint(String merchantId, String url, String secret, String subscribedEvents) {
        this.merchantId = merchantId;
        this.url = url;
        this.secret = secret;
        this.subscribedEvents = (subscribedEvents == null || subscribedEvents.isBlank()) ? "*" : subscribedEvents;
        this.active = true;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public boolean isSubscribedTo(String eventType) {
        if ("*".equals(this.subscribedEvents.trim())) {
            return true;
        }
        List<String> events = Arrays.stream(this.subscribedEvents.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return events.contains("*") || events.contains(eventType);
    }

    public UUID getId() { return id; }
    public String getMerchantId() { return merchantId; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getSecret() { return secret; }
    public String getSubscribedEvents() { return subscribedEvents; }
    public void setSubscribedEvents(String subscribedEvents) { this.subscribedEvents = subscribedEvents; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
