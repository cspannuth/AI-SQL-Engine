import java.nio.file.Path;
import java.util.*;

/** Reproduce the movie join with deterministic scan counts rather than timing gates. */
public final class PerformanceTests {
    private static int assertions;
    private static final String MOVIE_QUERY = "SELECT Movie.name,Person.name FROM Movie,Director,Person "
            + "WHERE director_id=Person.id AND Director.movie_id=Movie.id "
            + "AND (Person.name='Joel Coen' OR Person.name='Ethan Coen') ORDER BY Movie.name;";

    public static void main(String[] args) {
        try {
            DBMS.init(Path.of(args[0]));
            if (args[1].equals("prepare")) { prepare(); }
            keyChecks(); plannerChecks(); mutationChecks();
            long started = System.nanoTime();
            QueryLimits limits = new QueryLimits(100, 100000, 1000000, 10000, 10000000, 30000);
            try (QueryBudget budget = QueryBudget.start(limits)) {
                QueryResult result = DBMS.execute(MOVIE_QUERY);
                check(result.getRows().equals(List.of(List.of("Movie 10", "Joel Coen"),
                        List.of("Movie 20", "Ethan Coen"))), "Movie join result");
                System.out.printf(Locale.ROOT, "Movie join: %d scanned rows, %.2f ms%n",
                        budget.getScannedRows(), (System.nanoTime() - started) / 1000000.0);
                check(budget.getScannedRows() <= 242, "Join avoids the Cartesian scan");
            }
            System.out.println(DBMS.execute("EXPLAIN QUERY PLAN " + MOVIE_QUERY).getRows());
            System.out.println("Performance checks passed: " + assertions);
        } finally { DBMS.shutdown(); }
    }

    private static void plannerChecks() {
        check(DBMS.execute("EXPLAIN QUERY PLAN " + MOVIE_QUERY).getRows().get(0).toString().contains("Person"),
                "Selective person filter drives the inner join");
        check(DBMS.execute("EXPLAIN QUERY PLAN SELECT * FROM Movie CROSS JOIN Person WHERE Person.name='Joel Coen';")
                .getRows().get(0).toString().contains("Movie"), "Explicit CROSS JOIN keeps source order");
        check(DBMS.execute("SELECT m.*,p.* FROM Movie m JOIN Person p ON m.id=p.id WHERE p.name='Joel Coen';")
                .getRows().equals(List.of(List.of("0000010", "Movie 10", "0000010", "Joel Coen"))),
                "Wildcard columns retain SQL source order after reordering");
        check(DBMS.execute("SELECT a.id,b.id FROM lookups a LEFT JOIN lookups b ON a.id=b.id AND b.id=1 "
                + "WHERE b.id IS NULL ORDER BY a.id;").getRows().equals(Arrays.asList(
                        Arrays.asList(2L,null), Arrays.asList(3L,null), Arrays.asList(4L,null))), "LEFT JOIN null rows survive");
        check(DBMS.execute("SELECT a.id,b.id FROM lookups a,lookups b "
                + "WHERE (a.id=1 OR b.id=2) AND a.id=b.id ORDER BY a.id;").getRows()
                .equals(List.of(List.of(1L,1L),List.of(2L,2L))), "OR predicate keeps all dependencies");
        check(DBMS.execute("SELECT a.id,(SELECT b.id FROM lookups b WHERE b.code=a.code) "
                + "FROM lookups a WHERE a.id=2;").getRows().equals(List.of(List.of(2L,2L))), "Correlated key lookup");
        DBMS.execute("CREATE TABLE IF NOT EXISTS spellings(id TEXT PRIMARY KEY);");
        if (DBMS.execute("SELECT COUNT(*) FROM spellings;").getRows().equals(List.of(List.of(0L)))) {
            DBMS.execute("INSERT INTO spellings VALUES('01'),('1');");
        }
        check(DBMS.execute("SELECT s.id FROM lookups n,spellings s WHERE n.id=1 AND s.id=n.id ORDER BY s.id;")
                .getRows().equals(List.of(List.of("01"),List.of("1"))), "Numeric affinity never uses an incompatible text index");
    }

    private static void mutationChecks() {
        DBMS.execute("BEGIN;");
        try {
            try (QueryBudget budget = QueryBudget.start(new QueryLimits(100,100000,2,1000,1000000,5000))) {
                DBMS.execute("INSERT INTO Director VALUES('0000001','0000002');");
                check(budget.getScannedRows() == 2, "Foreign keys probe two matching parent rows");
            }
            try (QueryBudget budget = QueryBudget.start(new QueryLimits(100,100000,1,1000,1000000,5000))) {
                DBMS.execute("INSERT INTO lookups VALUES(5,'five','different','key');");
                check(budget.getScannedRows() == 0, "Unique inserts probe indexes without scanning existing rows");
            }
            fails("INSERT INTO Director VALUES('0000001','missing');");
            DBMS.execute("UPDATE lookups SET code=CASE id WHEN 1 THEN 'two' WHEN 2 THEN 'one' ELSE code END WHERE id IN (1,2);");
            check(DBMS.execute("SELECT code FROM lookups WHERE id IN (1,2) ORDER BY id;").getRows()
                    .equals(List.of(List.of("two"),List.of("one"))), "Unique swaps validate the final state");
            DBMS.execute("SAVEPOINT keys;");
            DBMS.execute("ALTER TABLE lookups RENAME COLUMN b TO second;");
            DBMS.execute("ALTER TABLE lookups RENAME TO renamed_lookups;");
            oneScan("SELECT id FROM renamed_lookups WHERE a='a' AND second='b|c';", List.of(List.of(2L)));
            DBMS.execute("ROLLBACK TO keys;");
            DBMS.execute("RELEASE keys;");
        } finally { DBMS.execute("ROLLBACK;"); }
        oneScan("SELECT id FROM lookups WHERE a='a' AND b='b|c';", List.of(List.of(2L)));
    }

    private static void keyChecks() {
        DBMS.execute("CREATE TABLE IF NOT EXISTS lookups(id INTEGER PRIMARY KEY,code TEXT UNIQUE,a TEXT,b TEXT,UNIQUE(a,b));");
        if (DBMS.execute("SELECT COUNT(*) FROM lookups;").getRows().equals(List.of(List.of(0L)))) {
            DBMS.execute("INSERT INTO lookups VALUES(1,'one','a|b','c'),(2,'two','a','b|c'),(3,NULL,NULL,'c'),(4,NULL,NULL,'c');");
        }
        oneScan("SELECT id FROM lookups WHERE id='2.0';", List.of(List.of(2L)));
        oneScan("SELECT id FROM lookups WHERE rowid=2;", List.of(List.of(2L)));
        oneScan("SELECT id FROM lookups WHERE id=1.5;", List.of());
        oneScan("SELECT id FROM lookups WHERE code='two';", List.of(List.of(2L)));
        oneScan("SELECT id FROM lookups WHERE b='b|c' AND a='a';", List.of(List.of(2L)));
        oneScan("SELECT name FROM Person WHERE id='0000010';", List.of(List.of("Joel Coen")));
        fails("INSERT INTO lookups VALUES(9,'two','different','key');");
        fails("INSERT INTO lookups VALUES(9,'nine','a','b|c');");
        for (IndexDefinition index : IndexCatalog.forTable("lookups")) {
            if (index.internal()) { fails("DROP INDEX " + index.name() + ";"); }
        }
    }

    private static void oneScan(String sql, List<List<Object>> expected) {
        try (QueryBudget budget = QueryBudget.start(new QueryLimits(100,100000,1,1000,1000000,5000))) {
            check(DBMS.execute(sql).getRows().equals(expected), "Indexed result: " + sql);
            check(budget.getScannedRows() <= 1, "At most one candidate: " + sql);
        }
    }

    private static void fails(String sql) {
        try { DBMS.execute(sql); }
        catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError("Expected rejection: " + sql);
    }

    private static void prepare() {
        DBMS.execute("CREATE TABLE Person(id CHAR(7) PRIMARY KEY,name TEXT);");
        DBMS.execute("CREATE TABLE Movie(id CHAR(7) PRIMARY KEY,name TEXT);");
        DBMS.execute("CREATE TABLE Director(movie_id CHAR(7) REFERENCES Movie(id),director_id CHAR(7) REFERENCES Person(id));");
        DBMS.execute("BEGIN;");
        for (int i = 0; i < 80; i++) {
            String id = String.format(Locale.ROOT, "%07d", i);
            String name = i == 10 ? "Joel Coen" : i == 20 ? "Ethan Coen" : "Person " + i;
            DBMS.execute("INSERT INTO Person VALUES(?,?);", List.of(id, name));
            DBMS.execute("INSERT INTO Movie VALUES(?,?);", List.of(id, "Movie " + i));
            DBMS.execute("INSERT INTO Director VALUES(?,?);", List.of(id, id));
        }
        DBMS.execute("COMMIT;");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) { throw new AssertionError(message); }
    }
}
