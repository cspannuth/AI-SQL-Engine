/** Finite read budgets. Working bytes are a conservative payload estimate. */
public record QueryLimits(int maxRows, long maxResultBytes, long maxScannedRows,
        int maxWorkingRows, long maxWorkingBytes, long timeoutMillis) {
    public QueryLimits {
        if (maxRows < 1 || maxResultBytes < 1 || maxScannedRows < 1
                || maxWorkingRows < 1 || maxWorkingBytes < 1 || timeoutMillis < 1
                || timeoutMillis > Long.MAX_VALUE / 1_000_000) {
            throw new IllegalArgumentException("Query limits must be positive and timeout must fit in nanoseconds");
        }
    }

    public static QueryLimits defaults() {
        return new QueryLimits(1_000, 1_048_576, 100_000, 10_000, 16_777_216, 5_000);
    }

    /** A request may lower its configured ceilings, never raise them. */
    public boolean permits(QueryLimits request) {
        return request.maxRows <= maxRows && request.maxResultBytes <= maxResultBytes
                && request.maxScannedRows <= maxScannedRows && request.maxWorkingRows <= maxWorkingRows
                && request.maxWorkingBytes <= maxWorkingBytes && request.timeoutMillis <= timeoutMillis;
    }
}
