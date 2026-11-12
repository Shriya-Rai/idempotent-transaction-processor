package com.mphasis.txprocessor;

import java.io.IOException;
import java.util.List;

public final class TransactionProcessingJob {
    private final TransactionSource source;
    private final TransactionProcessor processor;

    public TransactionProcessingJob(TransactionSource source, TransactionProcessor processor) {
        this.source = source;
        this.processor = processor;
    }

    public ProcessingSummary run() throws IOException {
        List<TransactionRequest> requests = source.load();
        processor.processBatch(requests);
        return processor.summary();
    }
}