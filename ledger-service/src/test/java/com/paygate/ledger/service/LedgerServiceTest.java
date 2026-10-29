package com.paygate.ledger.service;

import com.paygate.ledger.dto.MerchantBalanceResponse;
import com.paygate.ledger.dto.PaymentLedgerBreakdownResponse;
import com.paygate.ledger.entity.LedgerEntry;
import com.paygate.ledger.enums.EntryDirection;
import com.paygate.ledger.enums.LedgerAccount;
import com.paygate.ledger.enums.LedgerEntryType;
import com.paygate.ledger.exception.PaymentLedgerNotFoundException;
import com.paygate.ledger.repository.LedgerEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    private LedgerService ledgerService;

    @BeforeEach
    void setUp() {
        ledgerService = new LedgerService(ledgerEntryRepository);
    }

    @Test
    void recordPaymentSettlement_createsBalancedEntries() {
        UUID paymentId = UUID.randomUUID();
        String merchantId = "merchant-123";
        long amountMinor = 10000L; // 100.00 INR
        String currency = "INR";

        when(ledgerEntryRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        List<LedgerEntry> entries = ledgerService.recordPaymentSettlement(paymentId, merchantId, amountMinor, currency);

        assertThat(entries).hasSize(3);

        LedgerEntry customerDebit = entries.stream()
                .filter(e -> e.getAccount() == LedgerAccount.CUSTOMER)
                .findFirst().orElseThrow();
        assertThat(customerDebit.getDirection()).isEqualTo(EntryDirection.DEBIT);
        assertThat(customerDebit.getAmountMinor()).isEqualTo(10000L);

        LedgerEntry merchantCredit = entries.stream()
                .filter(e -> e.getAccount() == LedgerAccount.MERCHANT_SETTLEMENT)
                .findFirst().orElseThrow();
        assertThat(merchantCredit.getDirection()).isEqualTo(EntryDirection.CREDIT);
        assertThat(merchantCredit.getAmountMinor()).isEqualTo(9800L); // 10000 - 200

        LedgerEntry feeCredit = entries.stream()
                .filter(e -> e.getAccount() == LedgerAccount.PLATFORM_FEES)
                .findFirst().orElseThrow();
        assertThat(feeCredit.getDirection()).isEqualTo(EntryDirection.CREDIT);
        assertThat(feeCredit.getAmountMinor()).isEqualTo(200L); // 2% fee

        // Fundamental Double-Entry Theorem Verification: Sum(Debits) == Sum(Credits)
        long totalDebits = entries.stream()
                .filter(e -> e.getDirection() == EntryDirection.DEBIT)
                .mapToLong(LedgerEntry::getAmountMinor)
                .sum();
        long totalCredits = entries.stream()
                .filter(e -> e.getDirection() == EntryDirection.CREDIT)
                .mapToLong(LedgerEntry::getAmountMinor)
                .sum();

        assertThat(totalDebits).isEqualTo(totalCredits).isEqualTo(10000L);
    }

    @Test
    void recordRefund_createsBalancedEntries() {
        UUID paymentId = UUID.randomUUID();
        String merchantId = "merchant-123";
        String refundId = UUID.randomUUID().toString();
        long refundAmountMinor = 3000L;
        String currency = "INR";

        when(ledgerEntryRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        List<LedgerEntry> entries = ledgerService.recordRefund(paymentId, merchantId, refundId, refundAmountMinor, currency);

        assertThat(entries).hasSize(2);

        LedgerEntry merchantDebit = entries.stream()
                .filter(e -> e.getAccount() == LedgerAccount.MERCHANT_SETTLEMENT)
                .findFirst().orElseThrow();
        assertThat(merchantDebit.getDirection()).isEqualTo(EntryDirection.DEBIT);
        assertThat(merchantDebit.getAmountMinor()).isEqualTo(3000L);
        assertThat(merchantDebit.getReferenceId()).isEqualTo(refundId);

        LedgerEntry customerCredit = entries.stream()
                .filter(e -> e.getAccount() == LedgerAccount.CUSTOMER)
                .findFirst().orElseThrow();
        assertThat(customerCredit.getDirection()).isEqualTo(EntryDirection.CREDIT);
        assertThat(customerCredit.getAmountMinor()).isEqualTo(3000L);
        assertThat(customerCredit.getReferenceId()).isEqualTo(refundId);

        long totalDebits = entries.stream()
                .filter(e -> e.getDirection() == EntryDirection.DEBIT)
                .mapToLong(LedgerEntry::getAmountMinor)
                .sum();
        long totalCredits = entries.stream()
                .filter(e -> e.getDirection() == EntryDirection.CREDIT)
                .mapToLong(LedgerEntry::getAmountMinor)
                .sum();

        assertThat(totalDebits).isEqualTo(totalCredits).isEqualTo(3000L);
    }

    @Test
    void getPaymentLedger_returnsBalancedBreakdown() {
        UUID paymentId = UUID.randomUUID();
        String merchantId = "merchant-123";

        LedgerEntry debit = new LedgerEntry(paymentId, merchantId, LedgerAccount.CUSTOMER, EntryDirection.DEBIT, 10000L, "INR", null, LedgerEntryType.PAYMENT_SETTLEMENT, "Customer charge");
        LedgerEntry credit1 = new LedgerEntry(paymentId, merchantId, LedgerAccount.MERCHANT_SETTLEMENT, EntryDirection.CREDIT, 9800L, "INR", null, LedgerEntryType.PAYMENT_SETTLEMENT, "Net settlement");
        LedgerEntry credit2 = new LedgerEntry(paymentId, merchantId, LedgerAccount.PLATFORM_FEES, EntryDirection.CREDIT, 200L, "INR", null, LedgerEntryType.PLATFORM_FEE, "Fee cut");

        when(ledgerEntryRepository.findByPaymentIdAndMerchantIdOrderByCreatedAtAsc(paymentId, merchantId))
                .thenReturn(List.of(debit, credit1, credit2));

        PaymentLedgerBreakdownResponse response = ledgerService.getPaymentLedger(paymentId, merchantId);

        assertThat(response.paymentId()).isEqualTo(paymentId);
        assertThat(response.balanced()).isTrue();
        assertThat(response.totalDebitsMinor()).isEqualTo(10000L);
        assertThat(response.totalCreditsMinor()).isEqualTo(10000L);
        assertThat(response.entries()).hasSize(3);
    }

    @Test
    void getPaymentLedger_notFound_throwsException() {
        UUID paymentId = UUID.randomUUID();
        String merchantId = "merchant-123";

        when(ledgerEntryRepository.findByPaymentIdAndMerchantIdOrderByCreatedAtAsc(paymentId, merchantId))
                .thenReturn(List.of());

        assertThatThrownBy(() -> ledgerService.getPaymentLedger(paymentId, merchantId))
                .isInstanceOf(PaymentLedgerNotFoundException.class)
                .hasMessageContaining("No ledger entries found");
    }

    @Test
    void getMerchantBalance_computesSettledBalanceCorrectly() {
        String merchantId = "merchant-123";

        when(ledgerEntryRepository.sumAmountByAccountAndDirection(merchantId, LedgerAccount.MERCHANT_SETTLEMENT, EntryDirection.CREDIT, "INR"))
                .thenReturn(19600L); // Two 100.00 payments net 98.00 each
        when(ledgerEntryRepository.sumAmountByAccountAndDirection(merchantId, LedgerAccount.MERCHANT_SETTLEMENT, EntryDirection.DEBIT, "INR"))
                .thenReturn(3000L);  // One 30.00 refund
        when(ledgerEntryRepository.sumAmountByAccount(merchantId, LedgerAccount.PLATFORM_FEES, "INR"))
                .thenReturn(400L);   // Two 2.00 fees
        when(ledgerEntryRepository.countByMerchantAndCurrency(merchantId, "INR"))
                .thenReturn(8L);

        MerchantBalanceResponse balance = ledgerService.getMerchantBalance(merchantId, "INR");

        assertThat(balance.merchantId()).isEqualTo(merchantId);
        assertThat(balance.currency()).isEqualTo("INR");
        assertThat(balance.settledBalanceMinor()).isEqualTo(16600L); // 19600 - 3000
        assertThat(balance.totalCreditsMinor()).isEqualTo(19600L);
        assertThat(balance.totalDebitsMinor()).isEqualTo(3000L);
        assertThat(balance.totalFeesMinor()).isEqualTo(400L);
        assertThat(balance.entryCount()).isEqualTo(8L);
    }
}
