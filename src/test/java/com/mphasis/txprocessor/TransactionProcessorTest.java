package com.mphasis.txprocessor;

import java.math.BigDecimal;
import java.util.List;

public final class TransactionProcessorTest {
    private int assertions;

    public static void main(String[] args) {
        new TransactionProcessorTest().runAll();
    }

    private void runAll() {
        sampleBatchCoversRequiredScenarios();
        missingSequenceWaitsUntilEarlierSequenceArrives();
        retryExhaustionDoesNotApplyALedgerEffect();
        exhaustedTransientFailureCanBeRecovered();
        conflictingTransactionIdIsRejected();
        System.out.println("PASS: " + assertions + " assertions");
    }

    private void sampleBatchCoversRequiredScenarios() {
        TransactionProcessor processor = processor(new FailFirstAttemptSimulator(), 3);
        processor.processBatch(new SampleTransactionSource().load());
        ProcessingSummary summary = processor.summary();

        check(count(summary, TransactionStatus.PROCESSED) == 4, "Four unique valid transactions process");
        check(count(summary, TransactionStatus.DUPLICATE) == 1, "Later duplicate is identified");
        check(count(summary, TransactionStatus.FAILED) == 1, "Invalid amount is isolated");
        check(summary.ledgerEntries().size() == 4, "Each successful transaction creates one ledger entry");
        check(summary.balances().get("ACC-201:USD").compareTo(new BigDecimal("85")) == 0,
                "Ordered credits and debit produce the expected balance");
        TransactionResult retried = resultFor(summary, "TXN-2005");
        check(retried.status() == TransactionStatus.PROCESSED && retried.attempts() == 2,
                "Transient failure succeeds on the second attempt");
    }

    private void missingSequenceWaitsUntilEarlierSequenceArrives() {
        TransactionProcessor processor = processor((request, attempt) -> false, 3);
        processor.processBatch(List.of(request("TXN-2", "REQ-2", 2, TransactionType.DEBIT, "40")));
        check(resultFor(processor.summary(), "TXN-2").status() == TransactionStatus.PENDING,
                "Sequence gap remains pending");

        processor.processBatch(List.of(request("TXN-1", "REQ-1", 1, TransactionType.CREDIT, "100")));
        ProcessingSummary summary = processor.summary();
        check(resultFor(summary, "TXN-1").status() == TransactionStatus.PROCESSED,
                "Earlier sequence is processed when received");
        check(resultFor(summary, "TXN-2").status() == TransactionStatus.PROCESSED,
                "Pending sequence resumes after the gap is filled");
        check(summary.balances().get("ACC-1:USD").compareTo(new BigDecimal("60")) == 0,
                "Sequence ordering determines the final balance");
    }

    private void retryExhaustionDoesNotApplyALedgerEffect() {
        TransactionProcessor processor = processor((request, attempt) -> true, 2);
        TransactionRequest request = new TransactionRequest("TXN-FAIL", "REQ-FAIL", 1,
                "ACC-1", TransactionType.CREDIT, new BigDecimal("20"), "USD", true);
        processor.processBatch(List.of(request));
        ProcessingSummary summary = processor.summary();

        check(resultFor(summary, "TXN-FAIL").status() == TransactionStatus.FAILED,
                "Record fails after retry exhaustion");
        check(resultFor(summary, "TXN-FAIL").attempts() == 2, "Maximum attempts are enforced");
        check(summary.ledgerEntries().isEmpty(), "Failed retries do not create ledger entries");
        check(summary.balances().isEmpty(), "Failed retries do not update balances");
    }

    private void conflictingTransactionIdIsRejected() {
        TransactionProcessor processor = processor((request, attempt) -> false, 3);
        processor.processBatch(List.of(request("TXN-1", "REQ-1", 1, TransactionType.CREDIT, "10")));
        processor.processBatch(List.of(request("TXN-1", "REQ-OTHER", 2, TransactionType.CREDIT, "99")));
        ProcessingSummary summary = processor.summary();

        check(count(summary, TransactionStatus.FAILED) == 1,
                "Reusing a transaction ID with different business data is rejected");
        check(summary.ledgerEntries().size() == 1, "Conflicting replay has no side effect");
    }

        private void exhaustedTransientFailureCanBeRecovered() {
        TransactionProcessor processor = processor((request, attempt) -> attempt <= 2, 2);
        TransactionRequest request = new TransactionRequest("TXN-RECOVER", "REQ-RECOVER", 1,
            "ACC-1", TransactionType.CREDIT, new BigDecimal("20"), "USD", true);
        processor.processBatch(List.of(request));

        check(resultFor(processor.summary(), "TXN-RECOVER").status() == TransactionStatus.FAILED,
            "Initial transient attempts can be exhausted");
        check(processor.retryFailed("TXN-RECOVER"), "Explicit recovery can process the failed record");
        ProcessingSummary summary = processor.summary();
        check(resultFor(summary, "TXN-RECOVER").status() == TransactionStatus.PROCESSED,
            "Recovered transaction reaches processed state");
        check(summary.ledgerEntries().size() == 1, "Recovery applies exactly one ledger entry");
        }

    private TransactionRequest request(
            String transactionId,
            String requestId,
            long sequence,
            TransactionType type,
            String amount) {
        return new TransactionRequest(transactionId, requestId, sequence, "ACC-1", type,
                new BigDecimal(amount), "USD", false);
    }

    private TransactionProcessor processor(TransientFailureSimulator simulator, int maximumAttempts) {
        return new TransactionProcessor(simulator, maximumAttempts);
    }

    private long count(ProcessingSummary summary, TransactionStatus status) {
        return summary.countsByStatus().getOrDefault(status, 0L);
    }

    private TransactionResult resultFor(ProcessingSummary summary, String transactionId) {
        return summary.results().stream()
                .filter(result -> result.request() != null
                        && transactionId.equals(result.request().transactionId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No result for " + transactionId));
    }

    private void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}