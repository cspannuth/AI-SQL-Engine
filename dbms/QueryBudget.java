import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;

/** Optional per-thread execution budget. Ordinary engine calls remain unlimited. */
public final class QueryBudget implements AutoCloseable {
    public static class Exceeded extends IllegalArgumentException {
        private final String limit;
        public Exceeded(String limit) {
            super("Query exceeded its " + limit + " limit");
            this.limit = limit;
        }
        public String getLimit() { return limit; }
    }

    private static final ThreadLocal<QueryBudget> CURRENT = new ThreadLocal<>();
    private final QueryLimits limits;
    private final LongSupplier clock;
    private final long started;
    private long scannedRows;
    private long workingRows;
    private long workingBytes;

    private QueryBudget(QueryLimits limits, LongSupplier clock) {
        if (CURRENT.get() != null) { throw new IllegalStateException("A query budget is already active"); }
        this.limits = java.util.Objects.requireNonNull(limits);
        this.clock = java.util.Objects.requireNonNull(clock);
        started = clock.getAsLong();
        CURRENT.set(this);
    }

    public static QueryBudget start(QueryLimits limits) { return new QueryBudget(limits, System::nanoTime); }
    static QueryBudget start(QueryLimits limits, LongSupplier clock) { return new QueryBudget(limits, clock); }
    public long getScannedRows() { return scannedRows; }
    public long getWorkingRows() { return workingRows; }
    public long getWorkingBytes() { return workingBytes; }

    /** Cooperative deadline checks also honor interruption from a host application. */
    public static void check() {
        QueryBudget budget = CURRENT.get();
        if (budget == null) { return; }
        if (Thread.currentThread().isInterrupted()) { throw new Exceeded("interruption"); }
        if (budget.clock.getAsLong() - budget.started >= budget.limits.timeoutMillis() * 1_000_000) {
            throw new Exceeded("time");
        }
    }

    public static void scannedRow() {
        check();
        QueryBudget budget = CURRENT.get();
        if (budget != null && ++budget.scannedRows > budget.limits.maxScannedRows()) {
            throw new Exceeded("scanned rows");
        }
    }

    public static void reserveWorkingRow(long bytes) {
        check();
        QueryBudget budget = CURRENT.get();
        if (budget != null && ++budget.workingRows > budget.limits.maxWorkingRows()) {
            throw new Exceeded("working rows");
        }
        reserveBytes(bytes);
    }

    /** Cumulative accounting is conservative when intermediate values are replaced. */
    public static void reserveBytes(long bytes) {
        check();
        QueryBudget budget = CURRENT.get();
        if (budget != null) {
            if (bytes < 0 || bytes > budget.limits.maxWorkingBytes() - budget.workingBytes) {
                throw new Exceeded("working bytes");
            }
            budget.workingBytes += bytes;
        }
    }

    public static void checkValueSize(long bytes) {
        QueryBudget budget = CURRENT.get();
        if (budget != null && bytes > budget.limits.maxWorkingBytes()) { throw new Exceeded("working bytes"); }
        check();
    }

    public static long resultRowLimit() {
        QueryBudget budget = CURRENT.get();
        return budget == null ? Long.MAX_VALUE : budget.limits.maxRows();
    }

    public static long resultByteLimit() {
        QueryBudget budget = CURRENT.get();
        return budget == null ? Long.MAX_VALUE : budget.limits.maxResultBytes();
    }

    /** Deterministic logical payload size, excluding Java object and JSON overhead. */
    public static long payloadBytes(Object value) {
        if (value == null) { return 1; }
        if (value instanceof Iterable<?>) {
            long bytes = 8;
            for (Object item : (Iterable<?>) value) { bytes += payloadBytes(item); }
            return bytes;
        }
        if (value instanceof Number || value instanceof Boolean) { return 8; }
        return 4L + value.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    public void close() {
        if (CURRENT.get() == this) { CURRENT.remove(); }
    }
}
