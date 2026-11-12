package com.mphasis.txprocessor;

import java.io.IOException;
import java.util.List;

public interface TransactionSource {
    List<TransactionRequest> load() throws IOException;
}