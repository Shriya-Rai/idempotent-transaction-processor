package com.mphasis.txprocessor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class TransactionProcessor {
    private static final System.Logger LOGGER = System.getLogger(TransactionProcessor.class.getName());

    private final TransientFailureSimulator failureSimulator;
    private final int maximumAttempts;
    private final List<TransactionResult> results = new ArrayList<>();
    private final Map<String, SeenRequest> requestsById = new HashMap<>();
    private final Map<String, SeenRequest> transactionsById = new HashMap<>();
    private final TreeMap<Long, PendingTransaction> pendingBySequence = new TreeMap<>();
    private final Map<String, BigDecimal> balances = new TreeMap<>();
    private final List<LedgerEntry> ledgerEntries = new ArrayList<>();
    private long nextExpectedSequence;

    public TransactionProcessor(TransientFailureSimulator failureSimulator, int maximumAttempts) {
        if (maximumAttempts < 1) {
            throw new IllegalArgumentException("maximumAttempts must be at least 1");
        }
        this.failureSimulator = failureSimulator;
        this.maximumAttempts = maximumAttempts;
        this.nextExpectedSequence = 1;
    }

    public synchronized void processBatch(List<TransactionRequest> batch) {
        for (TransactionRequest request : batch) {
            accept(request);
            processAvailableSequences();
        }
    }

    public synchronized boolean retryFailed(String transactionId) {
        SeenRequest seen = transactionsById.get(transactionId);
        if (seen == null) {
            return false;
        }
        TransactionResult current = results.get(seen.resultIndex());
        if (current.status() != TransactionStatus.FAILED
                || !current.message().equals("Transient failure; retry limit reached")) {
            return false;
        }

        updateResult(seen.resultIndex(), TransactionStatus.RETRY_PENDING,
                "Manual recovery requested", current.attempts());
        processOne(seen.request(), seen.resultIndex(), current.attempts());
        return results.get(seen.resultIndex()).status() == TransactionStatus.PROCESSED;
    }

    public synchronized ProcessingSummary summary() {
        Map<TransactionStatus, Long> counts = new EnumMap<>(TransactionStatus.class);
        for (TransactionStatus status : TransactionStatus.values()) {
            counts.put(status, 0L);
        }
        for (TransactionResult result : results) {
            counts.compute(result.status(), (status, count) -> count + 1);
        }
        return new ProcessingSummary(results, counts, balances, ledgerEntries, nextExpectedSequence);
    }

    private void accept(TransactionRequest request) {
        String structuralError = structuralError(request);
        if (structuralError != null) {
            addResult(request, TransactionStatus.FAILED, structuralError, 0);
            return;
        }

        SeenRequest previousRequest = isBlank(request.requestId()) ? null : requestsById.get(request.requestId());
        if (previousRequest != null) {
            TransactionStatus status = sameBusinessData(previousRequest.request(), request)
                    ? TransactionStatus.DUPLICATE : TransactionStatus.FAILED;
            addResult(request, status, status == TransactionStatus.DUPLICATE
                    ? "Request was already received" : "requestId was reused with different data", 0);
            return;
        }

        SeenRequest previousTransaction = isBlank(request.transactionId())
            ? null : transactionsById.get(request.transactionId());
        if (previousTransaction != null) {
            TransactionStatus status = sameBusinessData(previousTransaction.request(), request)
                    ? TransactionStatus.DUPLICATE : TransactionStatus.FAILED;
            addResult(request, status, status == TransactionStatus.DUPLICATE
                    ? "Business transaction was already received" : "transactionId was reused with different data", 0);
            return;
        }

        if (request.sequenceNumber() < nextExpectedSequence) {
            addResult(request, TransactionStatus.FAILED, "Sequence is older than the processing checkpoint", 0);
            return;
        }

        PendingTransaction atSequence = pendingBySequence.get(request.sequenceNumber());
        if (atSequence != null) {
            addResult(request, TransactionStatus.FAILED, "Sequence number is already assigned to another transaction", 0);
            return;
        }

        int resultIndex = addResult(request, TransactionStatus.RECEIVED, "Accepted for processing", 0);
        SeenRequest seen = new SeenRequest(request, resultIndex);
        if (!isBlank(request.requestId())) {
            requestsById.put(request.requestId(), seen);
        }
        if (!isBlank(request.transactionId())) {
            transactionsById.put(request.transactionId(), seen);
        }
        pendingBySequence.put(request.sequenceNumber(),
                new PendingTransaction(request, resultIndex, businessValidationError(request)));
        updateResult(resultIndex, TransactionStatus.PENDING, "Waiting for sequence " + nextExpectedSequence, 0);
    }

    private void processAvailableSequences() {
        while (pendingBySequence.containsKey(nextExpectedSequence)) {
            PendingTransaction pending = pendingBySequence.remove(nextExpectedSequence);
            TransactionRequest request = pending.request();
            int resultIndex = pending.resultIndex();
            updateResult(resultIndex, TransactionStatus.PROCESSING, "Processing", 0);

            if (pending.validationError() != null) {
                updateResult(resultIndex, TransactionStatus.FAILED, pending.validationError(), 0);
            } else {
                processOne(request, resultIndex, 0);
            }
            nextExpectedSequence++;
        }
    }

    private void processOne(TransactionRequest request, int resultIndex, int previousAttempts) {
        int attemptLimit = previousAttempts + maximumAttempts;
        for (int attempt = previousAttempts + 1; attempt <= attemptLimit; attempt++) {
            updateResult(resultIndex, TransactionStatus.PROCESSING, "Attempt " + attempt, attempt);
            if (failureSimulator.shouldFail(request, attempt)) {
                if (attempt == attemptLimit) {
                    updateResult(resultIndex, TransactionStatus.FAILED,
                            "Transient failure; retry limit reached", attempt);
                    return;
                }
                updateResult(resultIndex, TransactionStatus.RETRY_PENDING,
                        "Transient failure; retry scheduled", attempt);
                LOGGER.log(System.Logger.Level.WARNING,
                        "Transaction {0} failed transiently; retry {1} of {2}",
                        request.transactionId(), attempt + 1, maximumAttempts);
                continue;
            }

            String businessError = applyTransaction(request);
            if (businessError != null) {
                updateResult(resultIndex, TransactionStatus.FAILED, businessError, attempt);
                return;
            }
            updateResult(resultIndex, TransactionStatus.PROCESSED, "Transaction applied", attempt);
            return;
        }
    }

    private String applyTransaction(TransactionRequest request) {
        String balanceKey = request.accountId() + ":" + request.currency();
        BigDecimal currentBalance = balances.getOrDefault(balanceKey, BigDecimal.ZERO);
        BigDecimal resultingBalance;
        if (request.transactionType() == TransactionType.DEBIT) {
            if (currentBalance.compareTo(request.amount()) < 0) {
                return "Insufficient balance";
            }
            resultingBalance = currentBalance.subtract(request.amount());
        } else {
            resultingBalance = currentBalance.add(request.amount());
        }

        ledgerEntries.add(new LedgerEntry(request.transactionId(), request.accountId(),
                request.transactionType(), request.amount(), request.currency(), resultingBalance));
        balances.put(balanceKey, resultingBalance);
        return null;
    }

    private String structuralError(TransactionRequest request) {
        if (request == null) {
            return "Transaction record is missing";
        }
        if (request.sequenceNumber() < 1) {
            return request.inputError() == null ? "sequenceNumber must be positive" : request.inputError();
        }
        return null;
    }

    private String businessValidationError(TransactionRequest request) {
        if (request.inputError() != null) {
            return request.inputError();
        }
        if (isBlank(request.transactionId())) {
            return "transactionId is required";
        }
        if (isBlank(request.requestId())) {
            return "requestId is required";
        }
        if (isBlank(request.accountId())) {
            return "accountId is required";
        }
        if (request.transactionType() == null) {
            return "transactionType is required";
        }
        if (request.amount() == null || request.amount().signum() <= 0) {
            return "amount must be greater than zero";
        }
        if (request.currency() == null || !request.currency().matches("[A-Z]{3}")) {
            return "currency must be a three-letter uppercase code";
        }
        return null;
    }

    private boolean sameBusinessData(TransactionRequest first, TransactionRequest second) {
        return equal(first.transactionId(), second.transactionId())
                && first.sequenceNumber() == second.sequenceNumber()
                && equal(first.accountId(), second.accountId())
                && first.transactionType() == second.transactionType()
                && equal(first.amount(), second.amount())
                && equal(first.currency(), second.currency());
    }

    private boolean equal(Object first, Object second) {
        return first == null ? second == null : first.equals(second);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private int addResult(TransactionRequest request, TransactionStatus status, String message, int attempts) {
        results.add(new TransactionResult(request, status, message, attempts));
        if (status == TransactionStatus.FAILED || status == TransactionStatus.DUPLICATE) {
            logStatus(request, status);
        }
        return results.size() - 1;
    }

    private void updateResult(int index, TransactionStatus status, String message, int attempts) {
        TransactionResult current = results.get(index);
        results.set(index, new TransactionResult(current.request(), status, message, attempts));
        if (status == TransactionStatus.FAILED || status == TransactionStatus.DUPLICATE
                || status == TransactionStatus.PROCESSED) {
            logStatus(current.request(), status);
        }
    }

    private void logStatus(TransactionRequest request, TransactionStatus status) {
        if (request != null) {
            LOGGER.log(System.Logger.Level.INFO, "Transaction {0}: {1}", request.transactionId(), status);
        }
    }

    private record SeenRequest(TransactionRequest request, int resultIndex) {
    }

    private record PendingTransaction(TransactionRequest request, int resultIndex, String validationError) {
    }
}