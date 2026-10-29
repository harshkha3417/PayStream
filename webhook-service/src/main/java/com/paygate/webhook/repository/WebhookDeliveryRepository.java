package com.paygate.webhook.repository;

import com.paygate.webhook.entity.WebhookDelivery;
import com.paygate.webhook.enums.DeliveryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    Page<WebhookDelivery> findByMerchantId(String merchantId, Pageable pageable);

    Page<WebhookDelivery> findByEndpointId(UUID endpointId, Pageable pageable);

    Optional<WebhookDelivery> findByIdAndMerchantId(UUID id, String merchantId);

    List<WebhookDelivery> findByStatusInAndNextRetryAtLessThanEqual(
            List<DeliveryStatus> statuses,
            Instant cutoff,
            Pageable pageable
    );
}
