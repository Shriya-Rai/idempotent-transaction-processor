package com.mphasis.txprocessor;

import java.math.BigDecimal;
import java.util.List;

public final class SampleTransactionSource implements TransactionSource {
    @Override
    public List<TransactionRequest> load() {
        return List.of(
                request("TXN-2002", "REQ-2", 2, "ACC-201", TransactionType.DEBIT, "40", false),
                request("TXN-2001", "REQ-1", 1, "ACC-201", TransactionType.CREDIT, "100", false),
                request("TXN-2002", "REQ-2-DUP", 2, "ACC-201", TransactionType.DEBIT, "40", false),
                request("TXN-2003", "REQ-3", 3, "ACC-201", TransactionType.CREDIT, "25", false),
                request("TXN-2004", "REQ-4", 4, "ACC-202", TransactionType.DEBIT, "-10", false),
                request("TXN-2005", "REQ-5", 5, "ACC-203", TransactionType.CREDIT, "500", true));
    }

    private TransactionRequest request(
            String transactionId,
            String requestId,
            long sequence,
            String accountId,
            TransactionType type,
            String amount,
            boolean simulateTransientFailure) {
        return new TransactionRequest(transactionId, requestId, sequence, accountId,
                type, new BigDecimal(amount), "USD", simulateTransientFailure);
    }
}