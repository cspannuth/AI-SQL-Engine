import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Integration checks for the local boundary, using disposable databases only. */
public class AdapterTests {
    private static int assertions;
    private static final QueryLimits DEFAULTS = QueryLimits.defaults();

    public static void main(String[] args) {
        Path home = Paths.get(args[0]);
        try {
            seed(home);
            DatabaseAdapter.AccessPolicy access = DatabaseAdapter.AccessPolicy.readTables(
                    "reader", Set.of("SALES", "emptydata", "missing"));
            DatabaseAdapter adapter = DatabaseAdapter.open(home, access);
            try {
                discovery(adapter);
                parameters(adapter);
                authorization(adapter);
                limits(adapter);
                snapshots(adapter);
            } finally { adapter.close(); }
            adapter.close();
            fails(DatabaseAdapter.ErrorCode.CLOSED, adapter::listTables);
            retention(home, access);
            isolation(home, home.resolveSibling("other-database"), access);
            deterministicDeadline();
            rowIdentityMetadata(home);
            System.out.println("Adapter checks passed: " + assertions);
        } finally { DBMS.shutdown(); }
    }

    private static void seed(Path home) {
        DBMS.init(home);
        DBMS.execute("CREATE TABLE sales(id INTEGER PRIMARY KEY,category TEXT,amount REAL,note TEXT);");
        for (int i = 1; i <= 8; i++) {
            DBMS.execute("INSERT INTO sales VALUES(?,?,?,?);",
                    Arrays.asList(i, i <= 4 ? "A" : "B", i == 8 ? null : i * 10.0, "note" + i));
        }
        DBMS.execute("CREATE TABLE secrets(id INTEGER PRIMARY KEY,value TEXT);");
        DBMS.execute("INSERT INTO secrets VALUES(1,'restricted fixture');");
        DBMS.execute("CREATE TABLE emptydata(id INTEGER PRIMARY KEY,amount REAL,note TEXT);");
        DBMS.execute("CREATE TABLE \"Unicode\u0130\"(id INTEGER PRIMARY KEY);");
        DBMS.execute("INSERT INTO \"Unicode\u0130\" VALUES(1);");
        DBMS.shutdown();
    }

    private static void discovery(DatabaseAdapter adapter) {
        check(adapter.listTables().equals(List.of("emptydata", "sales")), "Only allowed tables are listed");
        DatabaseAdapter.TableSchema schema = adapter.describeTable("SaLeS");
        check(schema.name().equals("sales"), "Canonical table name");
        check(schema.columns().size() == 4, "Schema column count");
        check(schema.columns().get(0).primaryKey() && !schema.columns().get(0).nullable(), "Key nullability");
        check(schema.columns().get(0).byteLength() == 8, "New integer width");
        check(schema.columns().get(2).type().equals("REAL"), "Stored column type");
        check(schema.rowIdTable() && schema.rowIdColumn().equals("id"), "Row identity metadata");
        fails(DatabaseAdapter.ErrorCode.ACCESS_DENIED, () -> adapter.describeTable("secrets"));
        fails(DatabaseAdapter.ErrorCode.NOT_FOUND, () -> adapter.describeTable("missing"));
        DatabaseAdapter.ReadResult empty = adapter.queryReadOnly("SELECT amount,note FROM emptydata;");
        check(empty.rows().isEmpty() && empty.complete(), "Empty success is complete");
        check(empty.columns().get(0).type() == SqlType.REAL, "Empty results retain declared type");
        check(empty.columns().get(1).type() == SqlType.TEXT, "Empty text result type");
        check(adapter.queryReadOnly("SELECT amount FROM sales WHERE id=8;").columns().get(0).type() == SqlType.REAL,
                "Entirely-null results retain declared type");
        DatabaseAdapter.ReadResult aggregate = adapter.queryReadOnly(
                "SELECT COUNT(*),SUM(amount),AVG(amount),MIN(note) FROM emptydata;");
        check(aggregate.columns().stream().map(DatabaseAdapter.ResultColumn::type).toList()
                .equals(List.of(SqlType.INTEGER, SqlType.NUMERIC, SqlType.REAL, SqlType.TEXT)), "Aggregate result types");
        check(aggregate.rows().get(0).equals(Arrays.asList(0L, null, null, null)), "Empty aggregate values");
        check(adapter.getCapabilities().apiVersion() == 1, "API version");
        check(adapter.getCapabilities().maximumLimits().equals(DEFAULTS), "Configured ceilings exposed");
    }

    private static void rowIdentityMetadata(Path home) {
        DBMS.init(home);
        DBMS.execute("CREATE TABLE loose_metadata(value TEXT);");
        DBMS.execute("INSERT INTO loose_metadata VALUES(NULL),('duplicate'),('duplicate');");
        DBMS.execute("CREATE TABLE key_metadata(code TEXT PRIMARY KEY);");
        DBMS.shutdown();
        try (DatabaseAdapter adapter = DatabaseAdapter.open(home, DatabaseAdapter.AccessPolicy.readAll("reader"))) {
            DatabaseAdapter.TableSchema loose = adapter.describeTable("loose_metadata");
            check(loose.rowIdTable() && loose.rowIdColumn() == null, "Hidden row ID metadata");
            check(loose.columns().size() == 1 && !loose.columns().get(0).primaryKey()
                    && loose.columns().get(0).nullable(), "No implicit first-column key");
            DatabaseAdapter.TableSchema text = adapter.describeTable("key_metadata");
            check(text.columns().get(0).primaryKey() && text.columns().get(0).nullable(), "Nullable ordinary key");
            DatabaseAdapter.ReadResult result = adapter.queryReadOnly("SELECT rowid,* FROM loose_metadata ORDER BY rowid;");
            check(result.rows().equals(List.of(Arrays.asList(1L,null),List.of(2L,"duplicate"),List.of(3L,"duplicate"))),
                    "Adapter returns deterministic row ID results");
            check(adapter.queryReadOnly("SELECT rowid FROM loose_metadata WHERE 0;").columns().get(0).type()
                    == SqlType.INTEGER, "Empty row ID results retain integer type");
        }
    }

    private static void parameters(DatabaseAdapter adapter) {
        String value = "'; DROP TABLE sales; --";
        DatabaseAdapter.ReadResult result = adapter.queryReadOnly("SELECT ? AS value,'?' AS literal /* ? */;", List.of(value));
        check(result.rows().get(0).equals(List.of(value, "?")), "Values never become SQL text");
        check(result.sql().contains("? AS value") && result.parameters().equals(List.of(value)), "Query provenance");
        result = adapter.queryReadOnly("SELECT ?,?,?,?;", Arrays.asList(null, true, 3, 1.5));
        check(result.rows().get(0).equals(Arrays.asList(null, 1L, 3L, 1.5)), "Typed parameters and nulls");
        check(result.columns().get(0).type() == SqlType.NULL, "Bound null type");
        result = adapter.queryReadOnly("SELECT id FROM sales WHERE id>? ORDER BY id LIMIT ? OFFSET ?;", List.of(2, 2, 1));
        check(result.rows().equals(List.of(List.of(4L), List.of(5L))), "Parameters across clauses bind in lexical order");
        check(adapter.queryReadOnly("SELECT id FROM sales ORDER BY id LIMIT ?,?;", List.of(1, 1))
                .rows().equals(List.of(List.of(2L))), "Comma LIMIT parameters");
        fails(DatabaseAdapter.ErrorCode.SQL_ERROR, () -> adapter.queryReadOnly("SELECT ?;"));
        fails(DatabaseAdapter.ErrorCode.SQL_ERROR, () -> adapter.queryReadOnly("SELECT 1;", List.of(1)));
        fails(DatabaseAdapter.ErrorCode.SQL_ERROR, () -> adapter.queryReadOnly("SELECT * FROM ?;", List.of("sales")));
        fails(DatabaseAdapter.ErrorCode.SQL_ERROR, () -> adapter.queryReadOnly("SELECT 1 LIMIT ?;", Arrays.asList((Object)null)));
        fails(DatabaseAdapter.ErrorCode.SQL_ERROR, () -> adapter.queryReadOnly("SELECT 1 LIMIT ?;", List.of(1.5)));
        fails(DatabaseAdapter.ErrorCode.INVALID_REQUEST, () -> adapter.queryReadOnly("SELECT ?;", List.of(Double.NaN)));
        fails(DatabaseAdapter.ErrorCode.INVALID_REQUEST, () -> adapter.queryReadOnly("SELECT ?;", List.of(new Object())));
        fails(DatabaseAdapter.ErrorCode.INVALID_REQUEST, () -> adapter.queryReadOnly("SELECT ?;", List.of("x".repeat(65_536))));
        fails(DatabaseAdapter.ErrorCode.INVALID_REQUEST, () -> adapter.queryReadOnly("SELECT 1; --" + "x".repeat(65_536)));
        fails(DatabaseAdapter.ErrorCode.INVALID_REQUEST, () -> adapter.queryReadOnly("SELECT ?;", Collections.nCopies(257, 1)));
        fails(DatabaseAdapter.ErrorCode.INVALID_REQUEST, () -> adapter.queryReadOnly(null));
        fails(DatabaseAdapter.ErrorCode.INVALID_REQUEST, () -> adapter.queryReadOnly(
                "SELECT " + "(".repeat(33) + "1" + ")".repeat(33) + ";"));
        fails(DatabaseAdapter.ErrorCode.INVALID_REQUEST, () -> adapter.queryReadOnly("SELECT " + "NOT ".repeat(33) + "1;"));
        fails(DatabaseAdapter.ErrorCode.INVALID_REQUEST, () -> adapter.queryReadOnly("SELECT " + "1+".repeat(256) + "1;"));
    }

    private static void authorization(DatabaseAdapter adapter) {
        for (String sql : List.of("DELETE FROM sales;", "UPDATE sales SET amount=0;", "DROP TABLE sales;",
                "CREATE TABLE forbidden(id INT);", "INSERT INTO sales VALUES(9,'x',0,NULL);", "BEGIN;", "COMMIT;", "ROLLBACK;")) {
            fails(DatabaseAdapter.ErrorCode.ACCESS_DENIED, () -> adapter.queryReadOnly(sql));
        }
        fails(DatabaseAdapter.ErrorCode.SQL_ERROR, () -> adapter.queryReadOnly("SELECT 1; DELETE FROM sales;"));
        fails(DatabaseAdapter.ErrorCode.ACCESS_DENIED, () -> adapter.queryReadOnly("/* allowed? */ SELECT * FROM secrets;"));
        fails(DatabaseAdapter.ErrorCode.ACCESS_DENIED, () -> adapter.queryReadOnly("SELECT s.id FROM sales s JOIN secrets x ON s.id=x.id;"));
        fails(DatabaseAdapter.ErrorCode.ACCESS_DENIED, () -> adapter.queryReadOnly("SELECT * FROM \"secrets\" sales;"));
        check(adapter.queryReadOnly("SELECT COUNT(*) FROM sales;").rows().get(0).equals(List.of(8L)), "Rejected writes changed nothing");
        DBMS.execute("BEGIN;");
        try {
            fails(DatabaseAdapter.ErrorCode.TRANSACTION_ACTIVE, () -> adapter.queryReadOnly("SELECT 1;"));
            check(DBMS.getTransaction() != null, "Adapter did not finish a host transaction");
        } finally { DBMS.execute("ROLLBACK;"); }
    }

    private static void limits(DatabaseAdapter adapter) {
        QueryLimits preview = budget(2, DEFAULTS.maxResultBytes(), DEFAULTS.maxScannedRows(),
                DEFAULTS.maxWorkingRows(), DEFAULTS.maxWorkingBytes());
        DatabaseAdapter.ReadResult result = adapter.queryReadOnly("SELECT id FROM sales ORDER BY id;", List.of(), preview);
        check(result.rows().equals(List.of(List.of(1L), List.of(2L))) && result.truncated(), "Row preview");
        check(result.truncationReason().equals("ROWS"), "Row truncation reason");
        check(!result.complete(), "A preview is not a complete population");
        result = adapter.queryReadOnly("SELECT id FROM sales LIMIT 2;", List.of(), preview);
        check(result.returnedRows() == 2 && result.complete(), "Completeness is relative to SQL LIMIT");
        result = adapter.queryReadOnly("SELECT id FROM sales WHERE id<3 ORDER BY id;", List.of(), preview);
        check(result.returnedRows() == 2 && result.complete(), "Exact boundary is complete");
        result = adapter.queryReadOnly("SELECT id FROM sales;", List.of(), preview);
        check(result.scannedRows() == 3 && result.truncated(), "Unordered preview stops after proving truncation");
        QueryLimits oneRow = budget(1, DEFAULTS.maxResultBytes(), DEFAULTS.maxScannedRows(),
                DEFAULTS.maxWorkingRows(), DEFAULTS.maxWorkingBytes());
        result = adapter.queryReadOnly("SELECT COUNT(*) FROM sales;", List.of(), oneRow);
        check(result.complete() && result.rows().equals(List.of(List.of(8L))), "Output caps do not truncate aggregate input");
        result = adapter.queryReadOnly("SELECT category,COUNT(*),SUM(amount) FROM sales GROUP BY category ORDER BY category;",
                List.of(), oneRow);
        check(result.truncated() && result.rows().equals(List.of(List.of("A", 4L, 100.0))), "Grouped previews retain full-group totals");
        result = adapter.queryReadOnly("SELECT DISTINCT category FROM sales ORDER BY category;", List.of(), oneRow);
        check(result.truncated() && result.rows().equals(List.of(List.of("A"))), "Distinct output preview");
        result = adapter.queryReadOnly("SELECT id FROM sales ORDER BY id LIMIT 5 OFFSET 2;", List.of(), preview);
        check(result.truncated() && result.rows().equals(List.of(List.of(3L), List.of(4L))), "Preview preserves SQL offset");
        result = adapter.queryReadOnly("SELECT ? AS value FROM sales ORDER BY id;", List.of("x".repeat(60)),
                budget(8, 120, DEFAULTS.maxScannedRows(), DEFAULTS.maxWorkingRows(), DEFAULTS.maxWorkingBytes()));
        check(result.returnedRows() == 1 && result.truncationReason().equals("BYTES"), "Byte preview");
        result = adapter.queryReadOnly("SELECT ? AS value FROM sales;", List.of("x".repeat(60)),
                budget(8, 60, DEFAULTS.maxScannedRows(), DEFAULTS.maxWorkingRows(), DEFAULTS.maxWorkingBytes()));
        check(result.returnedRows() == 0 && result.truncated(), "A row too large for a preview is not an empty success");
        fails(DatabaseAdapter.ErrorCode.LIMIT_EXCEEDED, () -> adapter.queryReadOnly("SELECT id FROM sales;", List.of(),
                budget(8, 1, DEFAULTS.maxScannedRows(), DEFAULTS.maxWorkingRows(), DEFAULTS.maxWorkingBytes())));
        fails(DatabaseAdapter.ErrorCode.LIMIT_EXCEEDED, () -> adapter.queryReadOnly("SELECT COUNT(*) FROM sales;", List.of(),
                budget(8, DEFAULTS.maxResultBytes(), 2, DEFAULTS.maxWorkingRows(), DEFAULTS.maxWorkingBytes())));
        try {
            adapter.queryReadOnly("SELECT COUNT(*) FROM sales;", List.of(),
                    budget(8, DEFAULTS.maxResultBytes(), 1, DEFAULTS.maxWorkingRows(), DEFAULTS.maxWorkingBytes()));
            throw new AssertionError("Expected a scan limit");
        } catch (DatabaseAdapter.AdapterException e) {
            check(e.getRequestId() != null && "scanned rows".equals(e.getLimit()), "Structured limit error details");
        }
        fails(DatabaseAdapter.ErrorCode.LIMIT_EXCEEDED, () -> adapter.queryReadOnly("SELECT id FROM sales ORDER BY id;", List.of(),
                budget(8, DEFAULTS.maxResultBytes(), DEFAULTS.maxScannedRows(), 2, DEFAULTS.maxWorkingBytes())));
        fails(DatabaseAdapter.ErrorCode.LIMIT_EXCEEDED, () -> adapter.queryReadOnly("SELECT ?;", List.of("x".repeat(200)),
                budget(8, DEFAULTS.maxResultBytes(), DEFAULTS.maxScannedRows(), DEFAULTS.maxWorkingRows(), 64)));
        fails(DatabaseAdapter.ErrorCode.LIMIT_EXCEEDED, () -> adapter.queryReadOnly(
                "SELECT category,COUNT(*) FROM sales GROUP BY category;", List.of(),
                budget(8, DEFAULTS.maxResultBytes(), DEFAULTS.maxScannedRows(), 1, DEFAULTS.maxWorkingBytes())));
        fails(DatabaseAdapter.ErrorCode.INVALID_REQUEST, () -> adapter.queryReadOnly("SELECT 1;", List.of(),
                budget(DEFAULTS.maxRows()+1, DEFAULTS.maxResultBytes(), DEFAULTS.maxScannedRows(),
                        DEFAULTS.maxWorkingRows(), DEFAULTS.maxWorkingBytes())));
        Thread.currentThread().interrupt();
        try { fails(DatabaseAdapter.ErrorCode.LIMIT_EXCEEDED, () -> adapter.queryReadOnly("SELECT 1;")); }
        finally { Thread.interrupted(); }
        check(DBMS.getTransaction() == null, "Failed bounded reads close their transaction");
        check(adapter.queryReadOnly("SELECT 42;").rows().equals(List.of(List.of(42L))), "Budget state does not leak");
    }

    private static void snapshots(DatabaseAdapter adapter) {
        DatabaseAdapter.ReadResult result = adapter.queryReadOnly("SELECT amount FROM sales WHERE id=1;");
        check(adapter.getResult(result.resultId()) == result, "Artifact retrieval");
        DBMS.execute("UPDATE sales SET amount=999 WHERE id=1;");
        check(adapter.getResult(result.resultId()).rows().get(0).equals(List.of(10.0)), "Artifact is an immutable snapshot");
        unsupported(() -> result.rows().get(0).set(0, 0));
        unsupported(() -> result.rows().clear());
        unsupported(() -> result.columns().clear());
        unsupported(() -> result.parameters().clear());
        check(result.callerId().equals("reader") && result.capturedAt().isBefore(result.expiresAt()), "Artifact provenance");
        fails(DatabaseAdapter.ErrorCode.NOT_FOUND, () -> adapter.getResult("unknown"));
        DBMS.execute("UPDATE sales SET amount=10 WHERE id=1;");
    }

    private static void retention(Path home, DatabaseAdapter.AccessPolicy access) {
        MutableClock clock = new MutableClock();
        String oldId;
        try (DatabaseAdapter adapter = new DatabaseAdapter(home, access, DEFAULTS,
                new DatabaseAdapter.Retention(2, 10_000, Duration.ofSeconds(1)), clock)) {
            oldId = adapter.queryReadOnly("SELECT 1;").resultId();
            String second = adapter.queryReadOnly("SELECT 2;").resultId();
            String third = adapter.queryReadOnly("SELECT 3;").resultId();
            fails(DatabaseAdapter.ErrorCode.NOT_FOUND, () -> adapter.getResult(oldId));
            check(adapter.getResult(second).rows().equals(List.of(List.of(2L))), "Retained second result");
            clock.now = clock.now.plusSeconds(1);
            fails(DatabaseAdapter.ErrorCode.NOT_FOUND, () -> adapter.getResult(third));
        }
        try (DatabaseAdapter adapter = DatabaseAdapter.open(home, access, DEFAULTS,
                new DatabaseAdapter.Retention(10, 140, Duration.ofMinutes(1)))) {
            String first = adapter.queryReadOnly("SELECT 1;").resultId();
            String second = adapter.queryReadOnly("SELECT 2;").resultId();
            fails(DatabaseAdapter.ErrorCode.NOT_FOUND, () -> adapter.getResult(first));
            check(adapter.getResult(second).returnedRows() == 1, "Retention byte ceiling");
            fails(DatabaseAdapter.ErrorCode.NOT_FOUND, () -> adapter.getResult(oldId));
        }
        try (DatabaseAdapter adapter = DatabaseAdapter.open(home, access, DEFAULTS,
                new DatabaseAdapter.Retention(1, 1, Duration.ofMinutes(1)))) {
            fails(DatabaseAdapter.ErrorCode.LIMIT_EXCEEDED, () -> adapter.queryReadOnly("SELECT 1;"));
        }
        try (DatabaseAdapter adapter = DatabaseAdapter.open(home,
                DatabaseAdapter.AccessPolicy.readTables("other-reader", Set.of()))) {
            check(adapter.listTables().isEmpty(), "Empty policy exposes no schema");
            fails(DatabaseAdapter.ErrorCode.ACCESS_DENIED, () -> adapter.queryReadOnly("SELECT * FROM sales;"));
            fails(DatabaseAdapter.ErrorCode.NOT_FOUND, () -> adapter.getResult(oldId));
            check(adapter.queryReadOnly("SELECT 1;").returnedRows() == 1, "Table-free expressions are allowed");
        }
        try (DatabaseAdapter adapter = DatabaseAdapter.open(home,
                DatabaseAdapter.AccessPolicy.readTables("unicode-reader", Set.of("Unicodei")))) {
            check(adapter.queryReadOnly("SELECT id FROM \"Unicode\u0130\";").rows().equals(List.of(List.of(1L))),
                    "Table authorization matches catalog case folding");
        }
    }

    private static void isolation(Path first, Path second, DatabaseAdapter.AccessPolicy access) {
        DatabaseAdapter stale = DatabaseAdapter.open(first, access);
        DBMS.shutdown();
        DBMS.init(second);
        DBMS.execute("CREATE TABLE separate(id INTEGER PRIMARY KEY);");
        fails(DatabaseAdapter.ErrorCode.CLOSED, stale::listTables);
        stale.close();
        check(DBMS.execute("SELECT 1;").getRows().equals(List.of(List.of(1L))), "Stale close preserves the new engine");
        fails(DatabaseAdapter.ErrorCode.INVALID_REQUEST, () -> DatabaseAdapter.open(first, access));
        check(DBMS.getEnv() != null, "Failed open preserves the current engine");
        DBMS.shutdown();
        try (DatabaseAdapter adapter = DatabaseAdapter.open(second, DatabaseAdapter.AccessPolicy.readAll("owner"))) {
            check(adapter.listTables().equals(List.of("separate")), "Database directories remain isolated");
        }
    }

    private static void deterministicDeadline() {
        AtomicLong time = new AtomicLong();
        QueryLimits limits = new QueryLimits(1, 1000, 1, 1, 1000, 1);
        try (QueryBudget ignored = QueryBudget.start(limits, time::get)) {
            QueryBudget.check();
            time.set(1_000_000);
            try { QueryBudget.check(); throw new AssertionError("Deadline was not enforced"); }
            catch (QueryBudget.Exceeded expected) { check(expected.getLimit().equals("time"), "Deadline reason"); }
        }
        check(QueryBudget.resultRowLimit() == Long.MAX_VALUE, "Deadline scope cleaned up");
    }

    private static QueryLimits budget(int rows, long bytes, long scans, int workRows, long workBytes) {
        return new QueryLimits(rows, bytes, scans, workRows, workBytes, DEFAULTS.timeoutMillis());
    }

    private static void fails(DatabaseAdapter.ErrorCode code, Runnable action) {
        try { action.run(); }
        catch (DatabaseAdapter.AdapterException expected) {
            check(expected.getCode() == code, "Expected " + code + ", got " + expected.getCode() + ": " + expected.getMessage());
            return;
        }
        throw new AssertionError("Expected " + code);
    }

    private static void unsupported(Runnable mutation) {
        try { mutation.run(); }
        catch (UnsupportedOperationException expected) { assertions++; return; }
        throw new AssertionError("Result mutation was allowed");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) { throw new AssertionError(message); }
    }

    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2020-01-01T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
