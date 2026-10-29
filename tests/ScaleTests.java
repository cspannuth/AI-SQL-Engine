import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** A moderate fixture checks execution behavior, not machine-dependent timings. */
public final class ScaleTests {
    private static final int ROWS = 4096;
    private static int assertions;

    public static void main(String[] args) {
        Path home = Path.of(args[0]);
        try {
            if (args[1].equals("prepare")) {
                prepare(home);
            } else if (args[1].equals("verify")) {
                verify(home);
            } else {
                throw new IllegalArgumentException("Expected prepare or verify");
            }
            System.out.println("Scale " + args[1] + " passed: " + assertions + " assertions, " + ROWS + " input rows");
        } finally {
            DBMS.shutdown();
        }
    }

    private static void prepare(Path home) {
        DBMS.init(home);
        DBMS.execute("CREATE TABLE measurements(id INTEGER PRIMARY KEY,value INTEGER,label TEXT,category INTEGER,amount REAL);");
        DBMS.execute("BEGIN;");
        // Batches keep setup fast without changing normal SQL execution paths.
        for (int start = 1; start <= ROWS; start += 128) {
            StringBuilder sql = new StringBuilder("INSERT INTO measurements VALUES");
            for (int id = start; id < start + 128; id++) {
                if (id != start) { sql.append(','); }
                sql.append('(').append(id).append(',').append(id).append(",'label")
                        .append(id).append("',").append(id % 8).append(',').append(id / 2.0).append(')');
            }
            DBMS.execute(sql.append(';').toString());
        }
        DBMS.execute("COMMIT;");
        DBMS.execute("CREATE INDEX measurement_value ON measurements(value);");
        DBMS.execute("CREATE UNIQUE INDEX measurement_label ON measurements(label);");
        DBMS.execute("UPDATE measurements SET value=9000,label='changed' WHERE id=4095;");
        DBMS.execute("DELETE FROM measurements WHERE id=4096;");
        DBMS.execute("BEGIN;");
        DBMS.execute("SAVEPOINT rewind;");
        DBMS.execute("UPDATE measurements SET value=-1,label='temporary' WHERE id=4095;");
        DBMS.execute("DROP INDEX measurement_value;");
        DBMS.execute("ROLLBACK TO rewind;");
        DBMS.execute("RELEASE rewind;");
        DBMS.execute("COMMIT;");
        check(DBMS.execute("SELECT id FROM measurements WHERE value=-1;").getRows().isEmpty(),
                "Savepoint removed the updated index entry");
        check(DBMS.execute("SELECT id FROM measurements WHERE value=9000;").getRows().equals(List.of(List.of(4095L))),
                "Savepoint restored the original index entry");
        // main closes the environment; verification runs in a different JVM.
    }

    private static void verify(Path home) {
        try (DatabaseAdapter adapter = DatabaseAdapter.open(home, DatabaseAdapter.AccessPolicy.readAll("scale-reader"))) {
            QueryLimits oneScan = limits(10, 10000, 1, 100, 100000);
            DatabaseAdapter.ReadResult lookup = adapter.queryReadOnly(
                    "SELECT id,label FROM measurements WHERE value=?;", List.of(9000), oneScan);
            check(lookup.complete() && lookup.scannedRows() == 1
                    && lookup.rows().equals(List.of(List.of(4095L, "changed"))), "One-row indexed lookup after restart");
            check(adapter.queryReadOnly("SELECT id FROM measurements WHERE value=4095;", List.of(), oneScan).rows().isEmpty(),
                    "Old index value was removed");
            check(adapter.queryReadOnly("SELECT id FROM measurements WHERE value=4096;", List.of(), oneScan).rows().isEmpty(),
                    "Deleted row was removed from index");
            check(adapter.queryReadOnly("SELECT id FROM measurements WHERE label='changed';", List.of(), oneScan)
                    .rows().equals(List.of(List.of(4095L))), "Unique index maintained and rebuilt");

            QueryLimits preview = limits(25, 100000, 100000, 10000, 1000000);
            DatabaseAdapter.ReadResult result = adapter.queryReadOnly("SELECT id FROM measurements ORDER BY id;", List.of(), preview);
            check(result.returnedRows() == 25 && result.truncated() && !result.complete()
                    && "ROWS".equals(result.truncationReason()), "Large sorted preview is marked incomplete");
            check(result.rows().get(0).equals(List.of(1L)) && result.rows().get(24).equals(List.of(25L)), "Preview values and order");
            check(adapter.getResult(result.resultId()).truncated(), "Artifact preserves truncation status");
            result = adapter.queryReadOnly("SELECT id FROM measurements ORDER BY id LIMIT 25;", List.of(), preview);
            check(result.returnedRows() == 25 && result.complete(), "SQL LIMIT result is complete");
            result = adapter.queryReadOnly("SELECT label FROM measurements ORDER BY id;", List.of(),
                    limits(1000, 100, 100000, 10000, 1000000));
            check(result.returnedRows() > 0 && result.truncated() && "BYTES".equals(result.truncationReason()), "Byte-limited preview");

            long count = ROWS - 1;
            double sum = count * (count + 1) / 4.0;
            result = adapter.queryReadOnly("SELECT COUNT(*),SUM(amount) FROM measurements;", List.of(), preview);
            check(result.complete() && result.rows().equals(List.of(List.of(count, sum))), "Aggregate includes all input rows");
            result = adapter.queryReadOnly("WITH all_rows AS (SELECT * FROM measurements) SELECT COUNT(*) FROM all_rows;",
                    List.of(), preview);
            check(result.complete() && result.rows().equals(List.of(List.of(count))), "CTE retains input beyond preview size");
            result = adapter.queryReadOnly("SELECT category,COUNT(*),SUM(amount) FROM measurements GROUP BY category ORDER BY category;",
                    List.of(), preview);
            check(result.complete() && result.rows().size() == 8, "All groups returned");
            for (int category = 0; category < 8; category++) {
                long expectedCount = 0;
                double expectedSum = 0;
                for (int id = 1; id < ROWS; id++) {
                    if (id % 8 == category) { expectedCount++; expectedSum += id / 2.0; }
                }
                check(result.rows().get(category).equals(List.of((long) category, expectedCount, expectedSum)),
                        "Group values match independently calculated fixture totals");
            }

            expectLimit("scanned rows", () -> adapter.queryReadOnly("SELECT COUNT(*) FROM measurements;", List.of(),
                    limits(25, 100000, count - 1, 10000, 1000000)));
            expectLimit("working rows", () -> adapter.queryReadOnly("SELECT id FROM measurements ORDER BY id;", List.of(),
                    limits(25, 100000, 100000, 32, 1000000)));
            expectLimit("working bytes", () -> adapter.queryReadOnly("SELECT label FROM measurements ORDER BY id;", List.of(),
                    limits(25, 100000, 100000, 10000, 64)));
            check(DBMS.getTransaction() == null, "Failed bounded reads close their transactions");
            check(adapter.queryReadOnly("SELECT COUNT(*) FROM measurements;").rows().equals(List.of(List.of(count))),
                    "Successful query after limit failures");
            check(adapter.describeTable("measurements").indexes().size() == 2, "Both index definitions persisted");
        }
        deterministicDeadline(home);
    }

    /** Drive the actual scan past a synthetic deadline without a timing race. */
    private static void deterministicDeadline(Path home) {
        DBMS.init(home);
        AtomicLong ticks = new AtomicLong();
        QueryLimits limits = new QueryLimits(10, 10000, 100000, 10000, 1000000, 20);
        try (QueryBudget budget = QueryBudget.start(limits, () -> ticks.getAndAdd(1000000))) {
            try {
                DBMS.execute("SELECT COUNT(*) FROM measurements;");
                throw new AssertionError("Expected a query deadline");
            } catch (QueryBudget.Exceeded expected) {
                check("time".equals(expected.getLimit()) && budget.getScannedRows() > 0,
                        "Execution reaches the scan and reports the deadline limit");
            }
        }
        check(DBMS.getTransaction() == null, "Deadline failure closes the transaction");
        check(DBMS.execute("SELECT COUNT(*) FROM measurements;").getRows().equals(List.of(List.of((long) ROWS - 1))),
                "Deadline state does not leak into subsequent execution");
    }

    private static QueryLimits limits(int rows, long bytes, long scans, int workRows, long workBytes) {
        return new QueryLimits(rows, bytes, scans, workRows, workBytes, 5000);
    }

    private static void expectLimit(String reason, Runnable query) {
        try { query.run(); }
        catch (DatabaseAdapter.AdapterException expected) {
            check(expected.getCode() == DatabaseAdapter.ErrorCode.LIMIT_EXCEEDED && reason.equals(expected.getLimit()),
                    "Structured error for " + reason);
            return;
        }
        throw new AssertionError("Expected limit: " + reason);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) { throw new AssertionError(message); }
    }
}
