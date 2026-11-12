package com.mphasis.txprocessor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Comparator;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws IOException {
        TransactionSource source;
        if (args.length == 0 || args[0].equals("--sample")) {
            source = new SampleTransactionSource();
        } else if (args[0].startsWith("--csv=")) {
            source = new CsvTransactionSource(Path.of(args[0].substring("--csv=".length())));
        } else {
            throw new IllegalArgumentException("Usage: run.ps1 [--sample | --csv=path]");
        }

        TransactionProcessor processor = new TransactionProcessor(new FailFirstAttemptSimulator(), 3);
        ProcessingSummary summary = new TransactionProcessingJob(source, processor).run();
        printSummary(summary);
    }

    private static void printSummary(ProcessingSummary summary) {
        System.out.println("\nProcessing summary");
        for (TransactionStatus status : TransactionStatus.values()) {
            long count = summary.countsByStatus().getOrDefault(status, 0L);
            if (count > 0) {
                System.out.printf("  %-14s %d%n", status, count);
            }
        }
        System.out.println("Balances");
        summary.balances().entrySet().stream()
                .sorted(Comparator.comparing(java.util.Map.Entry::getKey))
                .forEach(entry -> System.out.printf("  %s = %s%n", entry.getKey(), format(entry.getValue())));
        System.out.println("Ledger entries: " + summary.ledgerEntries().size());
        System.out.println("Next expected sequence: " + summary.nextExpectedSequence());
        System.out.println("Results");
        for (TransactionResult result : summary.results()) {
            String transactionId = result.request() == null ? "<missing>" : result.request().transactionId();
            System.out.printf("  %-12s %-14s attempts=%d  %s%n",
                    transactionId, result.status(), result.attempts(), result.message());
        }
    }

    private static String format(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }
}