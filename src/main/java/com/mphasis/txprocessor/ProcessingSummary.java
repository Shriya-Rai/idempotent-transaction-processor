package com.mphasis.txprocessor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ProcessingSummary(
        List<TransactionResult> results,
        Map<TransactionStatus, Long> countsByStatus,
        Map<String, BigDecimal> balances,
        List<LedgerEntry> ledgerEntries,
        long nextExpectedSequence) {

    public ProcessingSummary {
        results = List.copyOf(results);
        countsByStatus = Map.copyOf(countsByStatus);
        balances = Map.copyOf(balances);
        ledgerEntries = List.copyOf(ledgerEntries);
    }
}