import java.nio.file.*;
import java.util.*;

/** Check actual index access and budgets, rather than just matching scan results. */
public final class IndexTests {
    public static void main(String[] args) {
        Path home = Paths.get(args[0]);
        try {
            DBMS.init(home);
            DBMS.execute("CREATE TABLE indexed(id INTEGER PRIMARY KEY,value INTEGER,label TEXT);");
            for (int i = 0; i < 100; i++) {
                DBMS.execute("INSERT INTO indexed VALUES(?,?,?);",List.of(i,i,"label"+i));
            }
            DBMS.execute("CREATE INDEX value_lookup ON indexed(value);");
            DBMS.execute("CREATE UNIQUE INDEX label_lookup ON indexed(label);");
            check(DBMS.execute("EXPLAIN QUERY PLAN SELECT id FROM indexed WHERE value=99;").getRows()
                    .toString().contains("USING INDEX value_lookup"), "Planner selected equality index");
            DBMS.execute("BEGIN;");
            DBMS.execute("UPDATE indexed SET value=200,label='changed' WHERE id=99;");
            check(DBMS.execute("SELECT id FROM indexed WHERE value=200;").getRows().equals(List.of(List.of(99L))), "Index updated");
            DBMS.execute("ROLLBACK;");
            check(DBMS.execute("SELECT id FROM indexed WHERE value=99;").getRows().equals(List.of(List.of(99L))), "Index rollback");
            DBMS.execute("ALTER TABLE indexed ADD COLUMN tail TEXT;");
            DBMS.execute("ALTER TABLE indexed RENAME label TO title;");
            DBMS.execute("ALTER TABLE indexed RENAME TO indexed_new;");
            DBMS.shutdown();
            try (DatabaseAdapter adapter = DatabaseAdapter.open(home, DatabaseAdapter.AccessPolicy.readAll("reader"))) {
                QueryLimits tight = new QueryLimits(10,10000,1,100,100000,5000);
                DatabaseAdapter.ReadResult result = adapter.queryReadOnly(
                        "SELECT id,title FROM indexed_new WHERE value=?;",List.of(99),tight);
                check(result.rows().equals(List.of(List.of(99L,"label99"))) && result.scannedRows()==1,
                        "Index survives ALTER/restart and reads only the matching row");
                check(adapter.describeTable("indexed_new").indexes().size()==2,"Index metadata exposed");
                check(adapter.queryReadOnly("EXPLAIN QUERY PLAN SELECT id FROM indexed_new WHERE value=99;")
                        .rows().toString().contains("SEARCH"),"Read-only plan inspection");
            }
            System.out.println("Index access checks passed: planner, maintenance, rollback, ALTER, restart, and scan budget");
        } finally { DBMS.shutdown(); }
    }
    private static void check(boolean condition,String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
