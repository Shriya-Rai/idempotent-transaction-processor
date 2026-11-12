package com.mphasis.txprocessor;

import java.math.BigDecimal;

public record LedgerEntry(
        String transactionId,
        String accountId,
        TransactionType transactionType,
        BigDecimal amount,
        String currency,
        BigDecimal resultingBalance) {
}