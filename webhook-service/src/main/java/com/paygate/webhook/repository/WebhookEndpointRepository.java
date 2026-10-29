package com.paygate.webhook.repository;

import com.paygate.webhook.entity.WebhookEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {

    List<WebhookEndpoint> findByMerchantId(String merchantId);

    List<WebhookEndpoint> findByMerchantIdAndActiveTrue(String merchantId);

    Optional<WebhookEndpoint> findByIdAndMerchantId(UUID id, String merchantId);
}
