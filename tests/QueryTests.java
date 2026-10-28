import java.nio.file.*;
import java.util.*;

/** Nested reads must preserve authorization, metadata, and complete intermediate results. */
public final class QueryTests {
    public static void main(String[] args) {
        Path home = Paths.get(args[0]);
        try {
            DBMS.init(home);
            DBMS.execute("CREATE TABLE visible(id INTEGER PRIMARY KEY);");
            DBMS.execute("CREATE TABLE hidden(id INTEGER PRIMARY KEY);");
            DBMS.execute("INSERT INTO hidden VALUES(1);");
            for (int i = 0; i < 20; i++) { DBMS.execute("INSERT INTO visible VALUES(?);",List.of(i)); }
            DBMS.execute("CREATE TABLE hidden_child(pid INTEGER REFERENCES visible(id) DEFERRABLE INITIALLY DEFERRED);");
            DBMS.execute("INSERT INTO hidden_child VALUES(1);");
            check(DBMS.execute("SELECT UNIXEPOCH('1970-01-01'),UNIXEPOCH('2000-01-01');").getRows()
                    .equals(List.of(List.of(0L,946684800L))),"UTC epoch function");
            DBMS.shutdown();
            try (DatabaseAdapter adapter = DatabaseAdapter.open(home,DatabaseAdapter.AccessPolicy.readTables("reader",Set.of("visible")))) {
                for (String sql : List.of("SELECT (SELECT id FROM hidden);",
                        "SELECT id FROM visible WHERE EXISTS(SELECT 1 FROM hidden);",
                        "SELECT id FROM visible WHERE id IN (SELECT id FROM hidden);",
                        "SELECT * FROM (SELECT * FROM hidden) d;",
                        "SELECT * FROM visible UNION SELECT * FROM hidden;",
                        "WITH x AS (SELECT * FROM hidden) SELECT * FROM x;",
                        "SELECT COALESCE((SELECT id FROM hidden),0);",
                        "SELECT CASE WHEN 1 THEN CAST((SELECT id FROM hidden) AS INTEGER) ELSE 0 END;",
                        "WITH x AS (SELECT 99 AS id) INSERT INTO visible SELECT id FROM x RETURNING *;",
                        "INSERT INTO visible VALUES(99) RETURNING *;",
                        "EXPLAIN QUERY PLAN WITH x AS (SELECT * FROM hidden) SELECT * FROM x;")) {
                    try { adapter.queryReadOnly(sql); throw new AssertionError("Hidden nested table accepted"); }
                    catch (DatabaseAdapter.AdapterException error) {
                        if (error.getCode()!=DatabaseAdapter.ErrorCode.ACCESS_DENIED) { throw error; }
                    }
                }
                QueryLimits one = new QueryLimits(1,10000,1000,1000,1000000,5000);
                QueryLimits oneScan = new QueryLimits(1,10000,1,100,100000,5000);
                check(adapter.queryReadOnly("SELECT id FROM visible LIMIT 1;",List.of(),oneScan).scannedRows()==1,
                        "Read query does not validate or scan hidden deferred references");
                check(adapter.queryReadOnly("WITH x AS (SELECT id FROM visible) SELECT COUNT(*) FROM x;",List.of(),one)
                        .rows().equals(List.of(List.of(20L))),"Inner CTE was not preview-truncated");
                check(adapter.queryReadOnly("SELECT COUNT(*) FROM (SELECT id FROM visible UNION ALL SELECT id FROM visible) d;",List.of(),one)
                        .rows().equals(List.of(List.of(40L))),"Compound intermediate was not preview-truncated");
                check(adapter.queryReadOnly("SELECT id FROM visible UNION ALL SELECT id FROM visible;",List.of(),one).truncated(),
                        "Final compound preview is marked truncated");
                check(adapter.queryReadOnly("WITH x AS (SELECT id FROM visible) SELECT id FROM x WHERE 0;")
                        .columns().get(0).type()==SqlType.INTEGER,"Empty CTE metadata");
                check(adapter.queryReadOnly("SELECT (SELECT id FROM visible WHERE 0) WHERE 0;")
                        .columns().get(0).type()==SqlType.INTEGER,"Empty scalar subquery metadata");
                check(adapter.queryReadOnly("SELECT (SELECT id FROM visible WHERE id=?) AS n;",List.of(3))
                        .rows().equals(List.of(List.of(3L))),"Bound nested parameter");
                check(adapter.queryReadOnly("SELECT MIN('a','b') AS text_min;").columns().get(0).type()==SqlType.TEXT,
                        "Scalar function metadata follows argument types");
                check(adapter.queryReadOnly("SELECT 1 AS n UNION ALL SELECT 'text';").columns().get(0).type()==SqlType.UNKNOWN,
                        "Mixed compound result does not falsely advertise an integer column");
                check(adapter.queryReadOnly("SELECT 1 AS n UNION ALL SELECT 1.5;").columns().get(0).type()==SqlType.NUMERIC,
                        "Compound numeric types merge");
                check(adapter.queryReadOnly("SELECT (SELECT COUNT(*) FROM visible)>0;").scannedRows()==20,
                        "Comparison executes a nested query once");
            }
            System.out.println("Query scope checks passed: nested policy, parameters, affinity, metadata, and intermediate budgets");
        } finally { DBMS.shutdown(); }
    }
    private static void check(boolean condition,String message) { if(!condition) { throw new AssertionError(message); } }
}
