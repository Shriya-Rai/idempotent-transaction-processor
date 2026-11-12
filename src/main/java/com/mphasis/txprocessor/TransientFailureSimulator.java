package com.mphasis.txprocessor;

@FunctionalInterface
public interface TransientFailureSimulator {
    boolean shouldFail(TransactionRequest request, int attemptNumber);
}