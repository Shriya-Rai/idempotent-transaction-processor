package com.mphasis.txprocessor;

public final class FailFirstAttemptSimulator implements TransientFailureSimulator {
    @Override
    public boolean shouldFail(TransactionRequest request, int attemptNumber) {
        return request.simulateTransientFailure() && attemptNumber == 1;
    }
}