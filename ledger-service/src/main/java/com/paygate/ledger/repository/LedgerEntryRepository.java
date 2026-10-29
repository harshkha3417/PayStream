package com.paygate.ledger.repository;

import com.paygate.ledger.entity.LedgerEntry;
import com.paygate.ledger.enums.EntryDirection;
import com.paygate.ledger.enums.LedgerAccount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByPaymentIdAndMerchantIdOrderByCreatedAtAsc(UUID paymentId, String merchantId);

    Page<LedgerEntry> findByMerchantIdOrderByCreatedAtDesc(String merchantId, Pageable pageable);

    List<LedgerEntry> findByMerchantIdOrderByCreatedAtDesc(String merchantId);

    @Query("SELECT COALESCE(SUM(e.amountMinor), 0) FROM LedgerEntry e " +
           "WHERE e.merchantId = :merchantId " +
           "AND e.account = :account " +
           "AND e.direction = :direction " +
           "AND (:currency IS NULL OR e.currency = :currency)")
    long sumAmountByAccountAndDirection(
            @Param("merchantId") String merchantId,
            @Param("account") LedgerAccount account,
            @Param("direction") EntryDirection direction,
            @Param("currency") String currency
    );

    @Query("SELECT COALESCE(SUM(e.amountMinor), 0) FROM LedgerEntry e " +
           "WHERE e.merchantId = :merchantId " +
           "AND e.account = :account " +
           "AND (:currency IS NULL OR e.currency = :currency)")
    long sumAmountByAccount(
            @Param("merchantId") String merchantId,
            @Param("account") LedgerAccount account,
            @Param("currency") String currency
    );

    @Query("SELECT COUNT(e) FROM LedgerEntry e " +
           "WHERE e.merchantId = :merchantId " +
           "AND (:currency IS NULL OR e.currency = :currency)")
    long countByMerchantAndCurrency(
            @Param("merchantId") String merchantId,
            @Param("currency") String currency
    );

    @Query("SELECT e.currency FROM LedgerEntry e " +
           "WHERE e.merchantId = :merchantId " +
           "ORDER BY e.createdAt DESC LIMIT 1")
    Optional<String> findLatestCurrencyByMerchant(@Param("merchantId") String merchantId);
}
