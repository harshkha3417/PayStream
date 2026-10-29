package com.paygate.webhook.service;

import com.paygate.webhook.entity.WebhookDelivery;
import com.paygate.webhook.enums.DeliveryStatus;
import com.paygate.webhook.repository.WebhookDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class WebhookRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(WebhookRetryScheduler.class);

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDeliveryService deliveryService;

    public WebhookRetryScheduler(
            WebhookDeliveryRepository deliveryRepository,
            WebhookDeliveryService deliveryService) {
        this.deliveryRepository = deliveryRepository;
        this.deliveryService = deliveryService;
    }

    @Scheduled(fixedDelayString = "${webhook.retry-poll-interval-ms:2000}")
    public void retryPendingDeliveries() {
        List<WebhookDelivery> readyDeliveries = deliveryRepository.findByStatusInAndNextRetryAtLessThanEqual(
                List.of(DeliveryStatus.PENDING, DeliveryStatus.RETRYING),
                Instant.now(),
                PageRequest.of(0, 25)
        );

        if (!readyDeliveries.isEmpty()) {
            log.debug("Found {} webhook deliveries ready for retry", readyDeliveries.size());
            for (WebhookDelivery delivery : readyDeliveries) {
                try {
                    deliveryService.dispatchDelivery(delivery.getId());
                } catch (Exception e) {
                    log.error("Failed to retry delivery {}", delivery.getId(), e);
                }
            }
        }
    }
}
