package com.mphasis.txprocessor;

import java.math.BigDecimal;

public record TransactionRequest(
        String transactionId,
        String requestId,
        long sequenceNumber,
        String accountId,
        TransactionType transactionType,
        BigDecimal amount,
        String currency,
        boolean simulateTransientFailure,
        String inputError) {

    public TransactionRequest(
            String transactionId,
            String requestId,
            long sequenceNumber,
            String accountId,
            TransactionType transactionType,
            BigDecimal amount,
            String currency,
            boolean simulateTransientFailure) {
        this(transactionId, requestId, sequenceNumber, accountId, transactionType,
                amount, currency, simulateTransientFailure, null);
    }
}