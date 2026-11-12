# Idempotent Transaction Processing Service

A small Java 21 project for the Mphasis practical FDE assessment. It reads transaction requests, validates them, processes them in sequence, protects against duplicate effects, retries simulated transient failures, and prints an operational summary.

The implementation uses only the JDK. It deliberately has no web UI, external queue, paid service, or database; the brief permits an in-memory implementation for this exercise.

## Run It

Requirements: JDK 21 and Windows PowerShell.

```powershell
./run.ps1
./run.ps1 --csv=sample-transactions.csv
./test.ps1
```

The default run uses the built-in sample source. The CSV source expects a header and eight comma-separated fields in this order:

```text
transactionId,requestId,sequenceNumber,accountId,transactionType,amount,currency,simulateTransientFailure
```

CSV fields cannot contain commas. Invalid rows are reported without stopping processing of other rows. `simulateTransientFailure` is a demo-only switch for exercising retries; it is not part of a real transaction contract.

## What Happens

1. `TransactionProcessingJob` loads requests from a `TransactionSource`.
2. `TransactionProcessor` validates identifiers, checks for replays, and places accepted requests into a sequence queue.
3. Only the next expected sequence is processed. Later sequences remain `PENDING` until the gap is filled.
4. A valid credit or debit is applied once to an in-memory balance and ledger.
5. A simulated transient error moves the request through `RETRY_PENDING`; a successful retry creates one ledger entry. Exhausted retries end in `FAILED` and can be explicitly resubmitted for recovery.
6. The job reports status counts, balances, ledger-entry count, and individual request outcomes.

The sample deliberately starts with sequence 2, then supplies sequence 1. It also contains a later duplicate with a different `requestId`, an invalid negative amount, and a transaction that fails once before succeeding.

## Design Decisions

### Idempotency

`requestId` identifies a delivery, while `transactionId` identifies the business transaction. Repeating either identifier with identical business data is reported as `DUPLICATE`. Reusing an identifier with changed business data is `FAILED` as a conflict. The transaction ID is recorded before its side effect, and processing is synchronized, so a retry cannot append a second ledger entry.

This example stores idempotency state and the ledger in memory. A restart loses that state. A production version should use durable tables with unique constraints on the relevant identifiers and update transaction state, ledger, and balance in one database transaction.

### Ordering

For this exercise, `sequenceNumber` is treated as one global, contiguous stream starting at 1. This matches the sequence values in the supplied test data. A gap leaves later events pending; an invalid record with a usable sequence is marked failed when its turn arrives, then the stream advances so one bad record does not block the rest of the batch.

The upstream contract must define whether sequence numbers are global or scoped to an account/partition. A real queue-backed service would normally track a checkpoint per partition, persist pending events, and define recovery for permanently missing sequence numbers.

### Failure and Consistency

The simulator fails the first attempt only when `simulateTransientFailure` is true. The runnable sample allows at most three attempts; tests also exercise retry exhaustion and explicit recovery through `retryFailed`. Only failures caused by exhausted transient retries can be resubmitted; invalid input and business conflicts are not blindly retried. The balance and ledger are changed only after validation and a successful attempt. `synchronized` keeps this in-memory example safe when callers share the processor, but it is not a substitute for distributed locking or database transactions across multiple service instances.

### Input and Observability

`TransactionSource` is the replaceable input boundary. `SampleTransactionSource` and `CsvTransactionSource` are provided; a queue, API, or database adapter could implement the same interface later. Logs include transaction identifier and outcome, not account details. The processing summary is the simple operational report requested by the assessment.

`REVERSAL` is intentionally not implemented: its meaning depends on which prior transaction it reverses, and that relationship is not present in the supplied model. The supported transaction types are the two present in the sample, `CREDIT` and `DEBIT`.

## Tests

Run `./test.ps1`. The dependency-free test runner verifies:

- Duplicate business transactions do not create additional ledger entries.
- Requests arriving out of order wait for a missing sequence and then resume.
- Invalid input does not stop later transactions.
- A transient failure can succeed on retry.
- Retry exhaustion does not update the balance or ledger.
- An exhausted transient failure can be explicitly recovered without duplicate effects.
- Reusing a transaction ID with different business data is rejected.

## Interview Walkthrough

You can explain the design in this order:

1. **Boundary:** the job depends on `TransactionSource`, so ingestion can change without rewriting transaction rules.
2. **Identity:** request IDs protect delivery retries; transaction IDs protect business effects. Payload conflicts are rejected rather than mistaken for duplicates.
3. **Ordering:** a sequence gap stays pending; processing advances only after the current sequence reaches a terminal result.
4. **Retry safety:** retries happen before applying the ledger effect, and the sample proves one failure followed by one successful application.
5. **Scope and trade-offs:** in-memory state and a single process keep the assessment small. They are not durable or horizontally scalable.
6. **Production next steps:** durable database constraints and transactions, partition-aware sequence checkpoints, queue acknowledgement after commit, backoff and dead-letter handling, metrics/tracing, and concurrency/integration tests.

The assessment permits AI assistance. Be ready to describe which suggestions you used, what you checked with tests, and the limitations you would address before production. Only claim AI usage that reflects your actual workflow.