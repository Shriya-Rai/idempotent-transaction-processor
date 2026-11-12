package com.mphasis.txprocessor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CsvTransactionSource implements TransactionSource {
    private final Path path;

    public CsvTransactionSource(Path path) {
        this.path = path;
    }

    @Override
    public List<TransactionRequest> load() throws IOException {
        List<String> lines = Files.readAllLines(path);
        List<TransactionRequest> requests = new ArrayList<>();
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            requests.add(parseLine(line, index + 1));
        }
        return requests;
    }

    private TransactionRequest parseLine(String line, int lineNumber) {
        String[] fields = line.split(",", -1);
        if (fields.length != 8) {
            return invalidRow(fields, lineNumber, "Expected 8 comma-separated fields");
        }

        long sequence = 0;
        BigDecimal amount = null;
        TransactionType type = null;
        boolean simulateFailure = false;
        String error = null;
        try {
            sequence = Long.parseLong(fields[2].trim());
        } catch (NumberFormatException exception) {
            error = "Invalid sequenceNumber";
        }
        try {
            type = TransactionType.valueOf(fields[4].trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            error = appendError(error, "Invalid transactionType");
        }
        try {
            amount = new BigDecimal(fields[5].trim());
        } catch (NumberFormatException exception) {
            error = appendError(error, "Invalid amount");
        }
        String retryFlag = fields[7].trim();
        if (retryFlag.equalsIgnoreCase("true") || retryFlag.equalsIgnoreCase("false")) {
            simulateFailure = Boolean.parseBoolean(retryFlag);
        } else {
            error = appendError(error, "simulateTransientFailure must be true or false");
        }

        return new TransactionRequest(fields[0].trim(), fields[1].trim(), sequence,
                fields[3].trim(), type, amount, fields[6].trim(), simulateFailure,
                error == null ? null : "CSV line " + lineNumber + ": " + error);
    }

    private TransactionRequest invalidRow(String[] fields, int lineNumber, String message) {
        String transactionId = fields.length > 0 ? fields[0].trim() : "";
        String requestId = fields.length > 1 ? fields[1].trim() : "";
        long sequence = 0;
        if (fields.length > 2) {
            try {
                sequence = Long.parseLong(fields[2].trim());
            } catch (NumberFormatException ignored) {
                sequence = 0;
            }
        }
        return new TransactionRequest(transactionId, requestId, sequence, "", null,
                null, "", false, "CSV line " + lineNumber + ": " + message);
    }

    private String appendError(String current, String next) {
        return current == null ? next : current + "; " + next;
    }
}