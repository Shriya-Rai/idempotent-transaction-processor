package com.mphasis.txprocessor;

public record TransactionResult(
        TransactionRequest request,
        TransactionStatus status,
        String message,
        int attempts) {
}