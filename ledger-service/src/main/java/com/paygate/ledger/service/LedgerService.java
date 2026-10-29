package com.paygate.ledger.service;

import com.paygate.ledger.dto.LedgerEntryResponse;
import com.paygate.ledger.dto.MerchantBalanceResponse;
import com.paygate.ledger.dto.PaymentLedgerBreakdownResponse;
import com.paygate.ledger.entity.LedgerEntry;
import com.paygate.ledger.enums.EntryDirection;
import com.paygate.ledger.enums.LedgerAccount;
import com.paygate.ledger.enums.LedgerEntryType;
import com.paygate.ledger.exception.PaymentLedgerNotFoundException;
import com.paygate.ledger.repository.LedgerEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);
    private static final double PLATFORM_FEE_RATE = 0.02; // 2% platform fee
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final String CACHE_PREFIX = "ledger:balance:";

    private final LedgerEntryRepository ledgerEntryRepository;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public LedgerService(LedgerEntryRepository ledgerEntryRepository) {
        this(ledgerEntryRepository, null, new ObjectMapper());
    }

    @Autowired
    public LedgerService(
            LedgerEntryRepository ledgerEntryRepository,
            @Autowired(required = false) StringRedisTemplate redis,
            ObjectMapper objectMapper) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.redis = redis;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /**
     * Records double-entry balanced accounting entries for a successful payment:
     * - Debit CUSTOMER account with the total transaction amount
     * - Credit MERCHANT_SETTLEMENT account with the net transaction amount (amount - fee)
     * - Credit PLATFORM_FEES account with the 2% fee cut
     * Mathematical Identity: Sum(Debits) == Sum(Credits)
     */
    @Transactional
    public List<LedgerEntry> recordPaymentSettlement(UUID paymentId, String merchantId, long amountMinor, String currency) {
        long platformFee = Math.round(amountMinor * PLATFORM_FEE_RATE);
        long netSettlement = amountMinor - platformFee;

        LedgerEntry customerDebit = new LedgerEntry(
                paymentId,
                merchantId,
                LedgerAccount.CUSTOMER,
                EntryDirection.DEBIT,
                amountMinor,
                currency,
                null,
                LedgerEntryType.PAYMENT_SETTLEMENT,
                "Customer charge for payment " + paymentId
        );

        LedgerEntry merchantCredit = new LedgerEntry(
                paymentId,
                merchantId,
                LedgerAccount.MERCHANT_SETTLEMENT,
                EntryDirection.CREDIT,
                netSettlement,
                currency,
                null,
                LedgerEntryType.PAYMENT_SETTLEMENT,
                "Net settlement for payment " + paymentId
        );

        LedgerEntry feeCredit = new LedgerEntry(
                paymentId,
                merchantId,
                LedgerAccount.PLATFORM_FEES,
                EntryDirection.CREDIT,
                platformFee,
                currency,
                null,
                LedgerEntryType.PLATFORM_FEE,
                "Platform fee cut for payment " + paymentId
        );

        List<LedgerEntry> entries = ledgerEntryRepository.saveAll(List.of(customerDebit, merchantCredit, feeCredit));
        log.info("Recorded payment settlement ledger for paymentId={}: customerDebit={}, merchantCredit={}, feeCredit={}, balanced={}",
                paymentId, amountMinor, netSettlement, platformFee, true);

        // Evict balance cache so reads reflect newly settled amounts immediately
        evictBalanceCache(merchantId);
        return entries;
    }

    /**
     * Records double-entry balanced accounting entries for a refund:
     * - Debit MERCHANT_SETTLEMENT account with the refunded amount
     * - Credit CUSTOMER account with the refunded amount
     * Mathematical Identity: Sum(Debits) == Sum(Credits)
     */
    @Transactional
    public List<LedgerEntry> recordRefund(UUID paymentId, String merchantId, String refundId, long amountMinor, String currency) {
        LedgerEntry merchantDebit = new LedgerEntry(
                paymentId,
                merchantId,
                LedgerAccount.MERCHANT_SETTLEMENT,
                EntryDirection.DEBIT,
                amountMinor,
                currency,
                refundId,
                LedgerEntryType.REFUND,
                "Merchant debit for refund " + refundId
        );

        LedgerEntry customerCredit = new LedgerEntry(
                paymentId,
                merchantId,
                LedgerAccount.CUSTOMER,
                EntryDirection.CREDIT,
                amountMinor,
                currency,
                refundId,
                LedgerEntryType.REFUND,
                "Customer credit for refund " + refundId
        );

        List<LedgerEntry> entries = ledgerEntryRepository.saveAll(List.of(merchantDebit, customerCredit));
        log.info("Recorded refund ledger for paymentId={}, refundId={}: merchantDebit={}, customerCredit={}, balanced={}",
                paymentId, refundId, amountMinor, amountMinor, true);

        // Evict balance cache so reads reflect refund deductions immediately
        evictBalanceCache(merchantId);
        return entries;
    }

    @Transactional(readOnly = true)
    public PaymentLedgerBreakdownResponse getPaymentLedger(UUID paymentId, String merchantId) {
        List<LedgerEntry> entries = ledgerEntryRepository.findByPaymentIdAndMerchantIdOrderByCreatedAtAsc(paymentId, merchantId);
        if (entries.isEmpty()) {
            throw new PaymentLedgerNotFoundException(paymentId);
        }

        long totalDebits = entries.stream()
                .filter(e -> e.getDirection() == EntryDirection.DEBIT)
                .mapToLong(LedgerEntry::getAmountMinor)
                .sum();

        long totalCredits = entries.stream()
                .filter(e -> e.getDirection() == EntryDirection.CREDIT)
                .mapToLong(LedgerEntry::getAmountMinor)
                .sum();

        boolean balanced = (totalDebits == totalCredits);
        String currency = entries.getFirst().getCurrency();
        List<LedgerEntryResponse> responseList = entries.stream().map(LedgerEntryResponse::fromEntity).toList();

        return new PaymentLedgerBreakdownResponse(
                paymentId,
                merchantId,
                currency,
                totalDebits,
                totalCredits,
                balanced,
                responseList
        );
    }

    /**
     * Cached balance endpoint: returns cached balance from Redis (<1ms) if present,
     * avoiding 5 sequential aggregate SUM queries against PostgreSQL.
     */
    public MerchantBalanceResponse getMerchantBalance(String merchantId, String requestedCurrency) {
        String cacheKey = CACHE_PREFIX + merchantId + ":" + (requestedCurrency != null ? requestedCurrency.toUpperCase() : "DEFAULT");
        String cached = safeRedisGet(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, MerchantBalanceResponse.class);
            } catch (Exception e) {
                log.warn("Failed to deserialize cached balance for merchant {}, falling back to DB: {}", merchantId, e.getMessage());
            }
        }

        MerchantBalanceResponse response = calculateMerchantBalance(merchantId, requestedCurrency);
        try {
            safeRedisSet(cacheKey, objectMapper.writeValueAsString(response), CACHE_TTL);
            if (requestedCurrency == null && response.currency() != null) {
                safeRedisSet(CACHE_PREFIX + merchantId + ":" + response.currency(), objectMapper.writeValueAsString(response), CACHE_TTL);
            }
        } catch (Exception e) {
            log.warn("Failed to cache balance for merchant {}: {}", merchantId, e.getMessage());
        }
        return response;
    }

    @Transactional(readOnly = true)
    public MerchantBalanceResponse calculateMerchantBalance(String merchantId, String requestedCurrency) {
        String currency = (requestedCurrency != null && !requestedCurrency.isBlank())
                ? requestedCurrency.toUpperCase()
                : ledgerEntryRepository.findLatestCurrencyByMerchant(merchantId).orElse("INR");

        long totalCredits = ledgerEntryRepository.sumAmountByAccountAndDirection(
                merchantId, LedgerAccount.MERCHANT_SETTLEMENT, EntryDirection.CREDIT, currency);

        long totalDebits = ledgerEntryRepository.sumAmountByAccountAndDirection(
                merchantId, LedgerAccount.MERCHANT_SETTLEMENT, EntryDirection.DEBIT, currency);

        long totalFees = ledgerEntryRepository.sumAmountByAccount(
                merchantId, LedgerAccount.PLATFORM_FEES, currency);

        long count = ledgerEntryRepository.countByMerchantAndCurrency(merchantId, currency);
        long settledBalance = totalCredits - totalDebits;

        return new MerchantBalanceResponse(
                merchantId,
                currency,
                settledBalance,
                totalCredits,
                totalDebits,
                totalFees,
                count
        );
    }

    @Transactional(readOnly = true)
    public Page<LedgerEntryResponse> getLedgerEntries(String merchantId, Pageable pageable) {
        return ledgerEntryRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable)
                .map(LedgerEntryResponse::fromEntity);
    }

    private void evictBalanceCache(String merchantId) {
        if (redis == null) return;
        try {
            Set<String> keys = redis.keys(CACHE_PREFIX + merchantId + ":*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (Exception e) {
            log.warn("Redis unavailable for balance cache eviction for merchant {}: {}", merchantId, e.getMessage());
        }
    }

    private String safeRedisGet(String key) {
        if (redis == null) return null;
        try {
            return redis.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis unavailable for GET {}, falling back to database: {}", key, e.getMessage());
            return null;
        }
    }

    private void safeRedisSet(String key, String value, Duration ttl) {
        if (redis == null) return;
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            log.warn("Redis unavailable for SET {}, continuing without cache update: {}", key, e.getMessage());
        }
    }
}
